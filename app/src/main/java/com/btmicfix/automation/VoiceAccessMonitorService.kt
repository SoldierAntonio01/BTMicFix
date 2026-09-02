package com.btmicfix.automation

import android.app.Notification
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.bluetooth.LeAudioCacheRefresher
import com.btmicfix.shizuku.LeAudioShizukuBridge
import com.btmicfix.util.Logger
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BTMicFix automatic Voice Access routing.
 *
 * GOAL:
 *
 * Voice Access NOT listening
 *        ↓
 * BTMicFix = Idle
 *
 * Voice Access starts listening
 *        ↓
 * Buds BLE microphone route ON
 *
 * Voice Access stops listening
 *        ↓
 * Buds communication route OFF
 * BTMicFix = Idle
 *
 *
 * IMPORTANT:
 *
 * There is NO permanent BLE communication route.
 *
 * There is NO polling loop.
 *
 * Android's AudioRecordingCallback tells us when
 * recording state actually changes.
 *
 *
 * DETECTION:
 *
 * First choice:
 *
 * Read Voice Access recording client UID using
 * Android's hidden AudioRecordingConfiguration.getClientUid()
 * through HiddenApiBypass.
 *
 * Fallback:
 *
 * If Samsung doesn't expose the UID, require:
 *
 * 1. Voice Access notification is present
 * 2. an active unsilenced voice-recognition recording exists
 *
 *
 * ROUTING OFF:
 *
 * clearCommunicationDevice()
 *
 * The LE Audio profile itself remains connected.
 *
 * That means music/Bluetooth does NOT need a full profile
 * reconnect every time Voice Access stops listening.
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
     * VOICE ACCESS IDENTIFICATION
     * ============================================================
     */

    private var voiceAccessUid:
        Int =
        UID_UNKNOWN

    /*
     * Used only as fallback if Samsung does not expose
     * getClientUid().
     */

    @Volatile
    private var voiceAccessNotificationPresent =
        false

    /*
     * ============================================================
     * CURRENT STATE
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
     * AUDIO RECORDING CALLBACK
     * ============================================================
     *
     * Event driven.
     *
     * No 250ms polling.
     * No repeating timer.
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

        /*
         * --------------------------------------------------------
         * HIDDEN API ACCESS
         * --------------------------------------------------------
         */

        try {

            HiddenApiBypass
                .setHiddenApiExemptions(
                    "Landroid/media/"
                )

            Logger.i(
                "Audio hidden API exemption enabled"
            )

        } catch (e: Throwable) {

            Logger.w(
                "Could not enable audio hidden API exemption: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
        }

        /*
         * --------------------------------------------------------
         * AUDIO MANAGER
         * --------------------------------------------------------
         */

        audioManager =
            getSystemService(
                AudioManager::class.java
            )

        /*
         * --------------------------------------------------------
         * ROUTING MANAGER
         * --------------------------------------------------------
         *
         * startMonitoring() does NOT activate BLE routing.
         *
         * It only watches devices and route changes.
         */

        routingManager =
            AudioRoutingManager(
                applicationContext
            )

        routingManager
            .startMonitoring()

        /*
         * --------------------------------------------------------
         * VOICE ACCESS UID
         * --------------------------------------------------------
         */

        voiceAccessUid =
            findVoiceAccessUid()

        Logger.i(
            "Voice Access application UID = $voiceAccessUid"
        )

        /*
         * --------------------------------------------------------
         * RECORDING CALLBACK
         * --------------------------------------------------------
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
                "Could not register audio recording callback",
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
            "BTMicFix Voice Access automation connected"
        )

        /*
         * Voice Access might already be running/listening.
         */

        refreshVoiceAccessNotificationPresence()

        checkCurrentRecordingState()
    }

    /*
     * ============================================================
     * VOICE ACCESS NOTIFICATION CHANGED
     * ============================================================
     *
     * We do NOT parse its wording.
     *
     * This is only a fallback signal if hidden UID detection
     * is unavailable.
     */

    override fun onNotificationPosted(
        sbn: StatusBarNotification
    ) {

        if (
            sbn.packageName ==
            VOICE_ACCESS_PACKAGE
        ) {

            refreshVoiceAccessNotificationPresence()

            checkCurrentRecordingState()
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification
    ) {

        if (
            sbn.packageName ==
            VOICE_ACCESS_PACKAGE
        ) {

            refreshVoiceAccessNotificationPresence()

            checkCurrentRecordingState()
        }
    }

    /*
     * ============================================================
     * FIND VOICE ACCESS APPLICATION UID
     * ============================================================
     */

    private fun findVoiceAccessUid():
        Int {

        return try {

            @Suppress("DEPRECATION")
            val info =
                packageManager
                    .getApplicationInfo(
                        VOICE_ACCESS_PACKAGE,
                        0
                    )

            info.uid

        } catch (e: Throwable) {

            Logger.w(
                "Could not find Voice Access application UID: " +
                    "${e.message}"
            )

            UID_UNKNOWN
        }
    }

    /*
     * ============================================================
     * HIDDEN CLIENT UID
     * ============================================================
     *
     * IMPORTANT:
     *
     * AudioRecordingConfiguration.clientUid is NOT part of the
     * public Android SDK.
     *
     * That's why the previous code failed at compile time.
     *
     * We invoke hidden getClientUid() by name instead.
     */

    private fun getHiddenClientUid(
        config:
            AudioRecordingConfiguration
    ): Int {

        return try {

            val value =
                HiddenApiBypass.invoke(
                    AudioRecordingConfiguration::class.java,
                    config,
                    "getClientUid"
                )

            when (value) {

                is Int ->
                    value

                is Number ->
                    value.toInt()

                else ->
                    UID_UNKNOWN
            }

        } catch (e: Throwable) {

            /*
             * Don't spam logs for every config change.
             *
             * UID_UNKNOWN tells the code below to use
             * our event-driven fallback detection.
             */

            UID_UNKNOWN
        }
    }

    /*
     * ============================================================
     * VOICE ACCESS NOTIFICATION PRESENCE
     * ============================================================
     */

    private fun refreshVoiceAccessNotificationPresence() {

        voiceAccessNotificationPresent =
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

        Logger.i(
            "Voice Access notification present = " +
                voiceAccessNotificationPresent
        )
    }

    /*
     * ============================================================
     * CURRENT RECORDING STATE
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
     * DETECT VOICE ACCESS RECORDING
     * ============================================================
     */

    private fun evaluateRecordingState(
        configs:
            List<AudioRecordingConfiguration>
    ) {

        /*
         * Voice Access may have been updated/reinstalled while
         * BTMicFix stayed running.
         */

        if (
            voiceAccessUid ==
            UID_UNKNOWN
        ) {

            voiceAccessUid =
                findVoiceAccessUid()
        }

        /*
         * ========================================================
         * METHOD 1
         * EXACT UID MATCH
         * ========================================================
         */

        var uidInformationAvailable =
            false

        var exactVoiceAccessRecording =
            false

        for (
            config in configs
        ) {

            val silenced =
                try {

                    config.isClientSilenced

                } catch (_: Throwable) {

                    false
                }

            if (silenced) {

                continue
            }

            val uid =
                getHiddenClientUid(
                    config
                )

            if (
                uid !=
                UID_UNKNOWN
            ) {

                uidInformationAvailable =
                    true
            }

            if (
                uid !=
                UID_UNKNOWN &&

                voiceAccessUid !=
                UID_UNKNOWN &&

                uid ==
                voiceAccessUid
            ) {

                exactVoiceAccessRecording =
                    true

                break
            }
        }

        /*
         * ========================================================
         * METHOD 2
         * FALLBACK
         * ========================================================
         *
         * Only used if Android/Samsung doesn't let us read
         * recording UIDs.
         *
         * We require:
         *
         * Voice Access notification present
         *
         * AND
         *
         * active voice-like recording.
         */

        val fallbackVoiceRecording =
            if (
                !uidInformationAvailable
            ) {

                voiceAccessNotificationPresent &&
                    configs.any {
                            config ->

                        isVoiceLikeRecording(
                            config
                        )
                    }

            } else {

                false
            }

        val recording =
            exactVoiceAccessRecording ||
                fallbackVoiceRecording

        /*
         * ========================================================
         * DEBUG
         * ========================================================
         */

        Logger.i(
            "Voice Access detection: " +
                "configs=${configs.size}, " +
                "uidInfo=$uidInformationAvailable, " +
                "exact=$exactVoiceAccessRecording, " +
                "notification=$voiceAccessNotificationPresent, " +
                "fallback=$fallbackVoiceRecording, " +
                "result=$recording"
        )

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

        /*
         * ========================================================
         * STATE CHANGED
         * ========================================================
         */

        if (recording) {

            Logger.i(
                "✓ Voice Access STARTED listening"
            )

            turnRoutingOn()

        } else {

            Logger.i(
                "✓ Voice Access STOPPED listening"
            )

            turnRoutingOff()
        }
    }

    /*
     * ============================================================
     * VOICE-LIKE FALLBACK RECORDING
     * ============================================================
     */

    private fun isVoiceLikeRecording(
        config:
            AudioRecordingConfiguration
    ): Boolean {

        val silenced =
            try {

                config.isClientSilenced

            } catch (_: Throwable) {

                false
            }

        if (silenced) {

            return false
        }

        val clientSource =
            try {

                config.clientAudioSource

            } catch (_: Throwable) {

                MediaRecorder.AudioSource.DEFAULT
            }

        /*
         * Prefer actual speech-recognition style sources.
         */

        return when (
            clientSource
        ) {

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
     * ROUTING ON
     * ============================================================
     */

    private fun turnRoutingOn() {

        /*
         * Already active/requested.
         */

        if (
            routeRequested
        ) {

            return
        }

        routeRequested =
            true

        Logger.i(
            "Voice Access ON -> requesting Buds BLE microphone"
        )

        /*
         * ========================================================
         * FAST PATH
         * ========================================================
         *
         * Your Fold already normally shows:
         *
         * Antonio's Buds4 Pro
         * BLE Headset / LE Audio
         *
         * If that endpoint already exists, there is no reason
         * to do cache/Shizuku/profile work.
         */

        val existingBle =
            routingManager
                .findFirstBluetoothCommunicationDevice()

        if (
            existingBle !=
            null
        ) {

            Logger.i(
                "BLE headset exists -> fast automatic routing"
            )

            val result =
                routingManager
                    .routeToBluetooth(
                        existingBle
                    )

            Logger.i(
                "Fast automatic route result: $result"
            )

            /*
             * If this somehow failed, let another recording
             * transition try again rather than constantly retrying.
             */

            if (
                result is
                AudioRoutingManager.RoutingState.Failed
            ) {

                routeRequested =
                    false
            }

            return
        }

        /*
         * ========================================================
         * SLOW PATH
         * ========================================================
         *
         * TYPE_BLE_HEADSET disappeared.
         *
         * Re-establish LE Audio in background.
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
         * Voice Access may already have stopped.
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
         * LE AUDIO UUID CACHE
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
            "Automatic mode connecting LE Audio profile"
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

        if (!connected) {

            Logger.w(
                "Automatic LE Audio connection failed"
            )

            routeRequested =
                false

            return
        }

        /*
         * Let Android expose TYPE_BLE_HEADSET.
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

        if (
            result is
            AudioRoutingManager.RoutingState.Failed
        ) {

            routeRequested =
                false
        }
    }

    /*
     * ============================================================
     * ROUTING OFF
     * ============================================================
     */

    private fun turnRoutingOff() {

        /*
         * Already idle.
         */

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
         * IMPORTANT:
         *
         * clearRouting() releases:
         *
         * communication device
         * MODE_IN_COMMUNICATION
         *
         * It does NOT intentionally:
         *
         * unpair the Buds
         * erase LE Audio cache
         * shut Bluetooth off
         * remove LE profile support
         */

        try {

            routingManager
                .clearRouting()

        } catch (e: Throwable) {

            Logger.e(
                "Could not release automatic BLE route",
                e
            )
        }
    }

    /*
     * ============================================================
     * SHOULD SLOW ACTIVATION CONTINUE?
     * ============================================================
     */

    private fun shouldRemainActive():
        Boolean {

        return routeRequested &&
            voiceAccessRecording
    }

    /*
     * ============================================================
     * LISTENER DISCONNECTED
     * ============================================================
     */

    override fun onListenerDisconnected() {

        Logger.w(
            "Voice Access automation listener disconnected"
        )

        voiceAccessNotificationPresent =
            false

        voiceAccessRecording =
            false

        turnRoutingOff()

        super.onListenerDisconnected()
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

        voiceAccessNotificationPresent =
            false

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
            UID_UNKNOWN =
            -1

        private const val
            BLE_SETTLE_MS =
            500L
    }
    }
