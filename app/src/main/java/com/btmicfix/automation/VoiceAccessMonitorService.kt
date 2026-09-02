package com.btmicfix.automation

import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.bluetooth.LeAudioCacheRefresher
import com.btmicfix.shizuku.LeAudioShizukuBridge
import com.btmicfix.util.Logger
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BTMicFix Voice Access automation.
 *
 * NEW DESIGN:
 *
 * We DO NOT try to guess Voice Access listening state
 * from notification words such as:
 *
 * "Pause"
 * "Start listening"
 * "Touch to start"
 *
 * Voice Access 17+ changed how listening mode is controlled.
 *
 * Instead:
 *
 * 1. NotificationListener confirms Voice Access is present.
 *
 * 2. AudioManager.AudioRecordingCallback tells us when
 *    voice recording ACTUALLY starts/stops.
 *
 * 3. Recording starts:
 *
 *        BLE Buds microphone route ON
 *
 * 4. Recording stops:
 *
 *        BLE communication route OFF
 *
 *
 * IMPORTANT:
 *
 * The actual LE Audio PROFILE remains connected.
 *
 * We are only switching the communication route.
 *
 * This avoids repeatedly tearing down and recreating LE Audio
 * and should preserve the smooth audio setup we already achieved.
 */
class VoiceAccessMonitorService :
    NotificationListenerService() {

    /*
     * ============================================================
     * AUDIO MANAGER
     * ============================================================
     */

    private lateinit var audioManager:
        AudioManager

    /*
     * ============================================================
     * BTMICFIX ROUTING
     * ============================================================
     */

    private lateinit var routingManager:
        AudioRoutingManager

    /*
     * ============================================================
     * WORKER
     * ============================================================
     */

    private val worker =
        Executors.newSingleThreadExecutor()

    /*
     * Prevent multiple connection attempts from running together.
     */

    private val activationRunning =
        AtomicBoolean(
            false
        )

    /*
     * ============================================================
     * STATE
     * ============================================================
     */

    @Volatile
    private var voiceAccessNotificationPresent =
        false

    @Volatile
    private var voiceRecordingActive =
        false

    @Volatile
    private var routeRequested =
        false

    /*
     * ============================================================
     * AUDIO RECORDING CALLBACK
     * ============================================================
     *
     * Android tells us whenever microphone recording state changes.
     *
     * NO POLLING.
     */

    private val recordingCallback =
        object :
            AudioManager.AudioRecordingCallback() {

            override fun onRecordingConfigChanged(
                configs:
                    List<AudioRecordingConfiguration>
            ) {

                super.onRecordingConfigChanged(
                    configs
                )

                handleRecordingConfigurations(
                    configs
                )
            }
        }

    /*
     * ============================================================
     * CREATE
     * ============================================================
     */

    override fun onCreate() {

        super.onCreate()

        Logger.i(
            "VoiceAccessMonitorService created"
        )

        audioManager =
            getSystemService(
                AudioManager::class.java
            )

        routingManager =
            AudioRoutingManager(
                applicationContext
            )

        /*
         * The routing manager itself remains
         * event-driven.
         */

        routingManager
            .startMonitoring()

        /*
         * Listen for actual Android recording changes.
         */

        try {

            audioManager
                .registerAudioRecordingCallback(
                    recordingCallback,
                    null
                )

            Logger.i(
                "Audio recording callback registered"
            )

        } catch (e: Throwable) {

            Logger.e(
                "Could not register recording callback",
                e
            )
        }
    }

    /*
     * ============================================================
     * NOTIFICATION LISTENER READY
     * ============================================================
     */

    override fun onListenerConnected() {

        super.onListenerConnected()

        Logger.i(
            "Notification listener connected"
        )

        refreshVoiceAccessPresence()

        /*
         * Read the current recording state immediately.
         *
         * This is important if Voice Access was already listening
         * before BTMicFix's service connected.
         */

        refreshCurrentRecordingState()
    }

    /*
     * ============================================================
     * VOICE ACCESS NOTIFICATION POSTED
     * ============================================================
     */

    override fun onNotificationPosted(
        sbn: StatusBarNotification
    ) {

        if (
            sbn.packageName ==
            VOICE_ACCESS_PACKAGE
        ) {

            Logger.i(
                "Voice Access notification posted/updated"
            )

            refreshVoiceAccessPresence()

            refreshCurrentRecordingState()
        }
    }

    /*
     * ============================================================
     * VOICE ACCESS NOTIFICATION REMOVED
     * ============================================================
     */

    override fun onNotificationRemoved(
        sbn: StatusBarNotification
    ) {

        if (
            sbn.packageName ==
            VOICE_ACCESS_PACKAGE
        ) {

            Logger.i(
                "Voice Access notification removed"
            )

            refreshVoiceAccessPresence()

            refreshCurrentRecordingState()
        }
    }

    /*
     * ============================================================
     * CHECK WHETHER VOICE ACCESS IS PRESENT
     * ============================================================
     *
     * We intentionally DO NOT parse the notification wording.
     *
     * We only care whether Voice Access itself currently has
     * an active notification.
     */

    private fun refreshVoiceAccessPresence() {

        val present =
            try {

                activeNotifications
                    ?.any {

                        it.packageName ==
                            VOICE_ACCESS_PACKAGE
                    }
                    ?: false

            } catch (e: Throwable) {

                Logger.w(
                    "Could not read active notifications: " +
                        "${e.message}"
                )

                false
            }

        voiceAccessNotificationPresent =
            present

        Logger.i(
            "Voice Access notification present = $present"
        )

        /*
         * If Voice Access disappeared completely,
         * release our communication route.
         */

        if (!present) {

            requestRouteOff()
        }
    }

    /*
     * ============================================================
     * GET CURRENT RECORDINGS
     * ============================================================
     */

    private fun refreshCurrentRecordingState() {

        val configs =
            try {

                audioManager
                    .activeRecordingConfigurations

            } catch (e: Throwable) {

                Logger.w(
                    "Could not read active recording configurations: " +
                        "${e.message}"
                )

                emptyList()
            }

        handleRecordingConfigurations(
            configs
        )
    }

    /*
     * ============================================================
     * HANDLE RECORDING STATE
     * ============================================================
     */

    private fun handleRecordingConfigurations(
        configs:
            List<AudioRecordingConfiguration>
    ) {

        /*
         * We're interested in active voice/microphone capture.
         *
         * Voice Access generally uses a voice-recognition capture
         * path, but Samsung/Google can expose slightly different
         * sources depending on Speech Services and Android version.
         */

        val activeVoiceRecording =
            configs.any {
                    config ->

                isUsefulVoiceRecording(
                    config
                )
            }

        if (
            voiceRecordingActive ==
            activeVoiceRecording
        ) {

            /*
             * Nothing changed.
             */

            return
        }

        voiceRecordingActive =
            activeVoiceRecording

        Logger.i(
            "Voice recording active = " +
                activeVoiceRecording
        )

        evaluateAutomationState()
    }

    /*
     * ============================================================
     * IS THIS A VOICE RECORDING?
     * ============================================================
     */

    private fun isUsefulVoiceRecording(
        config:
            AudioRecordingConfiguration
    ): Boolean {

        /*
         * Ignore clients Android says are silenced.
         */

        val silenced =
            try {

                config.isClientSilenced

            } catch (_: Throwable) {

                false
            }

        if (silenced) {

            return false
        }

        val source =
            try {

                config.clientAudioSource

            } catch (_: Throwable) {

                return true
            }

        /*
         * These cover Voice Access / speech recognition
         * while avoiding obvious non-voice recording paths.
         */

        return when (source) {

            MediaRecorder.AudioSource.VOICE_RECOGNITION ->
                true

            MediaRecorder.AudioSource.VOICE_COMMUNICATION ->
                true

            MediaRecorder.AudioSource.MIC ->
                true

            MediaRecorder.AudioSource.DEFAULT ->
                true

            MediaRecorder.AudioSource.UNPROCESSED ->
                true

            else ->
                false
        }
    }

    /*
     * ============================================================
     * AUTOMATION DECISION
     * ============================================================
     */

    private fun evaluateAutomationState() {

        /*
         * VOICE ACCESS PRESENT
         * +
         * MICROPHONE ACTUALLY RECORDING
         *
         * =
         *
         * BUDS MIC ROUTE ON
         */

        val shouldRoute =
            voiceAccessNotificationPresent &&
                voiceRecordingActive

        Logger.i(
            "Voice Access automation decision: " +
                "notification=$voiceAccessNotificationPresent, " +
                "recording=$voiceRecordingActive, " +
                "route=$shouldRoute"
        )

        if (shouldRoute) {

            requestRouteOn()

        } else {

            requestRouteOff()
        }
    }

    /*
     * ============================================================
     * ROUTE ON
     * ============================================================
     */

    private fun requestRouteOn() {

        if (routeRequested) {

            return
        }

        routeRequested =
            true

        Logger.i(
            "Voice Access listening -> requesting Buds BLE mic"
        )

        /*
         * ========================================================
         * FASTEST PATH
         * ========================================================
         *
         * Your screenshot already shows:
         *
         * Antonio's Buds4 Pro
         * BLE Headset / LE Audio
         *
         * If TYPE_BLE_HEADSET already exists, we do NOT need
         * Shizuku/cache/profile work again.
         */

        val existingBle =
            routingManager
                .findFirstBluetoothCommunicationDevice()

        if (
            existingBle !=
            null
        ) {

            Logger.i(
                "BLE headset already available -> fast routing"
            )

            val result =
                routingManager
                    .routeToBluetooth(
                        existingBle
                    )

            Logger.i(
                "Fast Voice Access route result: $result"
            )

            return
        }

        /*
         * BLE endpoint doesn't currently exist.
         *
         * Run the slower setup in the background.
         */

        if (
            !activationRunning
                .compareAndSet(
                    false,
                    true
                )
        ) {

            return
        }

        worker.execute {

            try {

                activateLeAudioSlowPath()

            } finally {

                activationRunning
                    .set(
                        false
                    )
            }
        }
    }

    /*
     * ============================================================
     * SLOW LE AUDIO ACTIVATION
     * ============================================================
     */

    private fun activateLeAudioSlowPath() {

        /*
         * User may have stopped Voice Access while
         * this was waiting.
         */

        if (
            !shouldStillBeActive()
        ) {

            routeRequested =
                false

            return
        }

        /*
         * ========================================================
         * FIND BUDS
         * ========================================================
         */

        val devices =
            routingManager
                .availableDevices
                .value

        val buds =
            devices
                .firstOrNull {

                    it.name.contains(
                        "Buds",
                        ignoreCase =
                            true
                    )
                }

        if (
            buds ==
            null
        ) {

            Logger.w(
                "Voice Access automation could not find Buds"
            )

            routeRequested =
                false

            return
        }

        val preferredName =
            buds.name

        /*
         * ========================================================
         * CACHE CHECK
         * ========================================================
         */

        Logger.i(
            "Checking LE Audio cache"
        )

        val refresh =
            LeAudioCacheRefresher
                .refresh(
                    context =
                        applicationContext,

                    preferredDeviceName =
                        preferredName
                )

        if (
            !shouldStillBeActive()
        ) {

            routeRequested =
                false

            return
        }

        if (
            !refresh.cacheUpdated ||
            !refresh.hasAscs
        ) {

            Logger.w(
                "LE Audio cache not ready"
            )

            routeRequested =
                false

            return
        }

        /*
         * ========================================================
         * LE AUDIO CONNECTION
         * ========================================================
         */

        Logger.i(
            "Connecting LE Audio profile"
        )

        val connectResult =
            LeAudioShizukuBridge
                .forceLeAudio(
                    context =
                        applicationContext,

                    preferredDeviceName =
                        preferredName
                )

        if (
            !shouldStillBeActive()
        ) {

            routeRequested =
                false

            return
        }

        val connected =
            connectResult.contains(
                "ACCEPTED - LE AUDIO CONNECTED"
            ) ||
                connectResult.contains(
                    "ACCEPTED - ALREADY CONNECTED"
                )

        if (!connected) {

            Logger.w(
                "LE Audio connection did not complete"
            )

            routeRequested =
                false

            return
        }

        /*
         * Give Android a short amount of time to expose
         * TYPE_BLE_HEADSET.
         */

        try {

            Thread.sleep(
                500L
            )

        } catch (
            _: InterruptedException
        ) {
        }

        if (
            !shouldStillBeActive()
        ) {

            routeRequested =
                false

            return
        }

        /*
         * ========================================================
         * FINAL BLE ROUTE
         * ========================================================
         */

        val result =
            routingManager
                .routeToFirstAvailableBluetooth()

        Logger.i(
            "Voice Access BLE route result: $result"
        )
    }

    /*
     * ============================================================
     * ROUTE OFF
     * ============================================================
     */

    private fun requestRouteOff() {

        if (!routeRequested) {

            return
        }

        routeRequested =
            false

        Logger.i(
            "Voice Access stopped recording -> " +
                "releasing BLE communication route"
        )

        try {

            routingManager
                .clearRouting()

        } catch (e: Throwable) {

            Logger.e(
                "Could not release Voice Access route",
                e
            )
        }
    }

    /*
     * ============================================================
     * SHOULD ACTIVATION CONTINUE?
     * ============================================================
     */

    private fun shouldStillBeActive():
        Boolean {

        return routeRequested &&
            voiceAccessNotificationPresent &&
            voiceRecordingActive
    }

    /*
     * ============================================================
     * LISTENER DISCONNECTED
     * ============================================================
     */

    override fun onListenerDisconnected() {

        Logger.w(
            "Notification listener disconnected"
        )

        voiceAccessNotificationPresent =
            false

        voiceRecordingActive =
            false

        requestRouteOff()

        super.onListenerDisconnected()
    }

    /*
     * ============================================================
     * DESTROY
     * ============================================================
     */

    override fun onDestroy() {

        Logger.i(
            "Voice Access automation destroyed"
        )

        voiceAccessNotificationPresent =
            false

        voiceRecordingActive =
            false

        requestRouteOff()

        try {

            audioManager
                .unregisterAudioRecordingCallback(
                    recordingCallback
                )

        } catch (_: Throwable) {
        }

        try {

            routingManager
                .stopMonitoring()

        } catch (_: Throwable) {
        }

        worker.shutdownNow()

        super.onDestroy()
    }

    /*
     * ============================================================
     * CONSTANTS
     * ============================================================
     */

    companion object {

        /*
         * Confirmed Google Play package name for Voice Access.
         */

        private const val
            VOICE_ACCESS_PACKAGE =
            "com.google.android.apps.accessibility.voiceaccess"
    }
    }
