package com.btmicfix.automation

import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.service.notification.NotificationListenerService
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.bluetooth.LeAudioCacheRefresher
import com.btmicfix.shizuku.LeAudioShizukuBridge
import com.btmicfix.util.Logger
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BTMicFix automatic Voice Access routing.
 *
 * TARGET BEHAVIOR:
 *
 * Voice Access NOT recording
 *          ↓
 * BTMicFix Idle
 *
 * Voice Access starts recording
 *          ↓
 * BLE Buds communication route ON
 *
 * Voice Access stops recording
 *          ↓
 * BLE communication route OFF
 *
 *
 * IMPORTANT:
 *
 * We identify Voice Access by its Android UID.
 *
 * We DO NOT depend on:
 *
 * - notification wording
 * - "Pause" button text
 * - Gemini text
 * - Voice Access UI
 *
 * We use Android's actual recording configuration.
 *
 *
 * ALSO IMPORTANT:
 *
 * Turning routing OFF does NOT intentionally disconnect
 * the LE Audio Bluetooth profile.
 *
 * We only release the communication device.
 *
 * This allows the Buds to stay connected without keeping
 * the microphone route permanently active.
 */
class VoiceAccessMonitorService :
    NotificationListenerService() {

    /*
     * ============================================================
     * AUDIO
     * ============================================================
     */

    private lateinit var audioManager:
        AudioManager

    private lateinit var routingManager:
        AudioRoutingManager

    /*
     * ============================================================
     * VOICE ACCESS UID
     * ============================================================
     */

    private var voiceAccessUid:
        Int =
        -1

    /*
     * ============================================================
     * STATE
     * ============================================================
     */

    @Volatile
    private var voiceAccessRecording =
        false

    @Volatile
    private var routeRequested =
        false

    /*
     * ============================================================
     * WORKER
     * ============================================================
     */

    private val worker =
        Executors.newSingleThreadExecutor()

    private val activationRunning =
        AtomicBoolean(
            false
        )

    /*
     * ============================================================
     * RECORDING CALLBACK
     * ============================================================
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

                evaluateRecordingState(
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
            "Voice Access automatic routing service created"
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
         * Start the routing manager's event-driven monitoring.
         *
         * This does NOT enable BLE routing.
         *
         * It only watches available audio devices.
         */

        routingManager
            .startMonitoring()

        /*
         * ========================================================
         * FIND VOICE ACCESS UID
         * ========================================================
         */

        voiceAccessUid =
            findVoiceAccessUid()

        Logger.i(
            "Voice Access UID = $voiceAccessUid"
        )

        /*
         * ========================================================
         * LISTEN FOR MICROPHONE RECORDING CHANGES
         * ========================================================
         */

        try {

            audioManager
                .registerAudioRecordingCallback(
                    recordingCallback,
                    null
                )

            Logger.i(
                "Voice Access recording callback registered"
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
     * LISTENER CONNECTED
     * ============================================================
     */

    override fun onListenerConnected() {

        super.onListenerConnected()

        Logger.i(
            "BTMicFix automation service connected"
        )

        /*
         * Voice Access might already be running when our
         * service starts.
         */

        checkCurrentRecordingState()
    }

    /*
     * ============================================================
     * FIND VOICE ACCESS UID
     * ============================================================
     */

    private fun findVoiceAccessUid():
        Int {

        return try {

            val info =
                packageManager
                    .getApplicationInfo(
                        VOICE_ACCESS_PACKAGE,
                        0
                    )

            info.uid

        } catch (e: Throwable) {

            Logger.e(
                "Could not find Voice Access package UID",
                e
            )

            -1
        }
    }

    /*
     * ============================================================
     * CURRENT RECORDINGS
     * ============================================================
     */

    private fun checkCurrentRecordingState() {

        val configs =
            try {

                audioManager
                    .activeRecordingConfigurations

            } catch (e: Throwable) {

                Logger.w(
                    "Could not read active recordings: " +
                        "${e.message}"
                )

                emptyList()
            }

        evaluateRecordingState(
            configs
        )
    }

    /*
     * ============================================================
     * DETECT VOICE ACCESS
     * ============================================================
     */

    private fun evaluateRecordingState(
        configs:
            List<AudioRecordingConfiguration>
    ) {

        /*
         * If Voice Access wasn't installed when this service
         * started, try to find it again.
         */

        if (
            voiceAccessUid <
            0
        ) {

            voiceAccessUid =
                findVoiceAccessUid()
        }

        /*
         * ========================================================
         * THE IMPORTANT CHECK
         * ========================================================
         *
         * Is there an ACTIVE recording whose client UID belongs
         * specifically to Google's Voice Access app?
         */

        val recording =
            configs.any {
                    config ->

                val uid =
                    try {

                        config.clientUid

                    } catch (_: Throwable) {

                        -1
                    }

                val silenced =
                    try {

                        config.isClientSilenced

                    } catch (_: Throwable) {

                        false
                    }

                uid ==
                    voiceAccessUid &&
                    !silenced
            }

        /*
         * Nothing changed.
         */

        if (
            recording ==
            voiceAccessRecording
        ) {

            return
        }

        voiceAccessRecording =
            recording

        if (recording) {

            Logger.i(
                "✓ Voice Access started microphone recording"
            )

            turnRoutingOn()

        } else {

            Logger.i(
                "✓ Voice Access stopped microphone recording"
            )

            turnRoutingOff()
        }
    }

    /*
     * ============================================================
     * ROUTING ON
     * ============================================================
     */

    private fun turnRoutingOn() {

        /*
         * Already requested.
         */

        if (
            routeRequested
        ) {

            return
        }

        routeRequested =
            true

        /*
         * ========================================================
         * FAST PATH
         * ========================================================
         *
         * Your phone already normally has:
         *
         * Antonio's Buds4 Pro
         * BLE Headset / LE Audio
         *
         * If it exists, routing takes only one call.
         */

        val existingBle =
            routingManager
                .findFirstBluetoothCommunicationDevice()

        if (
            existingBle !=
            null
        ) {

            Logger.i(
                "Voice Access ON -> fast BLE routing"
            )

            val result =
                routingManager
                    .routeToBluetooth(
                        existingBle
                    )

            Logger.i(
                "Voice Access fast route: $result"
            )

            return
        }

        /*
         * ========================================================
         * SLOW PATH
         * ========================================================
         *
         * Only needed if Android lost TYPE_BLE_HEADSET.
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
     * SLOW LE AUDIO SETUP
     * ============================================================
     */

    private fun activateLeAudioSlowPath() {

        /*
         * Voice Access may have stopped already.
         */

        if (
            !shouldRemainActive()
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
                "Automatic mode could not find Buds"
            )

            routeRequested =
                false

            return
        }

        val preferredName =
            buds.name

        /*
         * ========================================================
         * ANDROID LE AUDIO CACHE
         * ========================================================
         */

        Logger.i(
            "Automatic mode checking LE Audio cache"
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
            !shouldRemainActive()
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
                "Automatic LE Audio cache check failed"
            )

            routeRequested =
                false

            return
        }

        /*
         * ========================================================
         * LE AUDIO PROFILE
         * ========================================================
         */

        Logger.i(
            "Automatic mode connecting LE Audio"
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
            !shouldRemainActive()
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

        if (
            !connected
        ) {

            Logger.w(
                "Automatic LE Audio connection failed"
            )

            routeRequested =
                false

            return
        }

        /*
         * Give Android time to expose the BLE communication
         * endpoint.
         */

        try {

            Thread.sleep(
                BLE_SETTLE_MS
            )

        } catch (
            _: InterruptedException
        ) {
        }

        if (
            !shouldRemainActive()
        ) {

            routeRequested =
                false

            return
        }

        /*
         * ========================================================
         * FINAL ROUTE
         * ========================================================
         */

        val result =
            routingManager
                .routeToFirstAvailableBluetooth()

        Logger.i(
            "Automatic LE route result: $result"
        )
    }

    /*
     * ============================================================
     * ROUTING OFF
     * ============================================================
     */

    private fun turnRoutingOff() {

        if (
            !routeRequested
        ) {

            return
        }

        routeRequested =
            false

        Logger.i(
            "Voice Access OFF -> releasing BLE communication route"
        )

        /*
         * ========================================================
         * IMPORTANT
         * ========================================================
         *
         * This does NOT intentionally:
         *
         * - unpair Buds
         * - disconnect Bluetooth
         * - remove LE Audio cache
         * - disable LE Audio profile
         *
         * It just releases the communication/microphone route.
         */

        try {

            routingManager
                .clearRouting()

        } catch (e: Throwable) {

            Logger.e(
                "Could not release automatic route",
                e
            )
        }
    }

    /*
     * ============================================================
     * SHOULD ROUTE STILL BE ACTIVE?
     * ============================================================
     */

    private fun shouldRemainActive():
        Boolean {

        return routeRequested &&
            voiceAccessRecording
    }

    /*
     * ============================================================
     * DESTROY
     * ============================================================
     */

    override fun onDestroy() {

        Logger.i(
            "Voice Access automation service destroyed"
        )

        voiceAccessRecording =
            false

        turnRoutingOff()

        try {

            audioManager
                .unregisterAudioRecordingCallback(
                    recordingCallback
                )

        } catch (
            _: Throwable
        ) {
        }

        try {

            routingManager
                .stopMonitoring()

        } catch (
            _: Throwable
        ) {
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

        private const val
            VOICE_ACCESS_PACKAGE =
            "com.google.android.apps.accessibility.voiceaccess"

        private const val
            BLE_SETTLE_MS =
            500L
    }
    }
