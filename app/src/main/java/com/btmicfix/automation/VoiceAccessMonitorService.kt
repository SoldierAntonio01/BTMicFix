package com.btmicfix.automation

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.notification.NotificationListenerService
import com.btmicfix.BuildConfig
import com.btmicfix.IVoiceAccessOpCallback
import com.btmicfix.IVoiceAccessWatcher
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.shizuku.VoiceAccessAppOpsService
import com.btmicfix.util.Logger
import rikka.shizuku.Shizuku

/**
 * Voice Access -> Buds BLE microphone automation.
 *
 * There are now TWO routing layers:
 *
 * 1. Normal app process:
 *      AudioManager.setCommunicationDevice(TYPE_BLE_HEADSET)
 *
 * 2. Shizuku shell process:
 *      preferred capture-preset device = BLE HEADSET INPUT
 *
 * Layer 2 is important because Voice Access may have already opened
 * AudioRecord using the phone microphone by the time layer 1 reacts.
 *
 * No permanent polling loop is used.
 */
class VoiceAccessMonitorService :
    NotificationListenerService() {

    private lateinit var routingManager:
        AudioRoutingManager

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    @Volatile
    private var voiceAccessActive =
        false

    @Volatile
    private var routeRequested =
        false

    private var watcherBinding =
        false

    private var watcher:
        IVoiceAccessWatcher? =
        null

    /*
     * ============================================================
     * DELAYED ROUTE OFF
     * ============================================================
     */

    private val delayedRouteOff =
        Runnable {

            if (
                !voiceAccessActive
            ) {

                turnRoutingOffNow()
            }
        }

    /*
     * If STARTING fires but RECORD_AUDIO never becomes ACTIVE,
     * undo the temporary route and capture preference.
     */
    private val startingTimeout =
        Runnable {

            if (
                !voiceAccessActive
            ) {

                Logger.w(
                    "Voice Access STARTING did not become ACTIVE; releasing temporary route"
                )

                try {

                    watcher
                        ?.clearCapturePreference()

                } catch (_: Throwable) {
                }

                turnRoutingOffNow()

                VoiceAccessAutomationState.update {

                    it.copy(
                        capturePreferenceApplied =
                            false,

                        autoRoutingActive =
                            false,

                        lastMessage =
                            "Voice Access start attempt ended before RECORD_AUDIO became active."
                    )
                }
            }
        }

    /*
     * ============================================================
     * SHIZUKU USER SERVICE
     * ============================================================
     */

    private val watcherArgs:
        Shizuku.UserServiceArgs
        by lazy {

            Shizuku.UserServiceArgs(
                ComponentName(
                    BuildConfig.APPLICATION_ID,
                    VoiceAccessAppOpsService::class.java.name
                )
            )
                .daemon(
                    false
                )
                .tag(
                    "btmicfix_voice_access_appops"
                )
                /*
                 * IMPORTANT:
                 *
                 * Version bump forces Shizuku to destroy the old
                 * UserService and load this new implementation.
                 */
                .version(
                    WATCHER_SERVICE_VERSION
                )
                .processNameSuffix(
                    "voice_access_appops"
                )
                .debuggable(
                    BuildConfig.DEBUG
                )
        }

    /*
     * ============================================================
     * PRIVILEGED CALLBACK
     * ============================================================
     */

    private val appOpsCallback =
        object :
            IVoiceAccessOpCallback.Stub() {

            /*
             * EARLY SIGNAL.
             *
             * The Shizuku process has already tried to prefer the
             * BLE input for VOICE_RECOGNITION before this arrives.
             *
             * Select the BLE communication device immediately too.
             */
            override fun onRecordAudioStarting() {

                mainHandler.post {

                    VoiceAccessAutomationState.update {

                        it.copy(
                            startingEventSeen =
                                true,

                            lastMessage =
                                "Voice Access RECORD_AUDIO STARTING — priming Buds microphone."
                        )
                    }

                    mainHandler.removeCallbacks(
                        startingTimeout
                    )

                    turnRoutingOn()

                    /*
                     * Failed app-op starts do not necessarily produce
                     * a later ACTIVE -> INACTIVE pair.
                     */
                    mainHandler.postDelayed(
                        startingTimeout,
                        START_CONFIRM_TIMEOUT_MS
                    )
                }
            }

            /*
             * AUTHORITATIVE ON/OFF STATE.
             */
            override fun onRecordAudioActiveChanged(
                active: Boolean
            ) {

                mainHandler.post {

                    mainHandler.removeCallbacks(
                        startingTimeout
                    )

                    VoiceAccessAutomationState.update {

                        it.copy(
                            recordAudioActive =
                                active,

                            lastMessage =
                                if (active) {
                                    "Voice Access RECORD_AUDIO ACTIVE — keeping Buds mic route."
                                } else {
                                    "Voice Access RECORD_AUDIO INACTIVE — releasing Buds mic route."
                                }
                        )
                    }

                    Logger.i(
                        "Voice Access RECORD_AUDIO active = $active"
                    )

                    applyVoiceAccessState(
                        active
                    )
                }
            }

            /*
             * Tells the diagnostic card whether Android audio policy
             * accepted the BLE input as the preferred capture device.
             */
            override fun onCaptureRoutingChanged(
                applied: Boolean,
                audioSource: Int
            ) {

                mainHandler.post {

                    VoiceAccessAutomationState.update {

                        it.copy(
                            capturePreferenceApplied =
                                applied,

                            captureAudioSource =
                                audioSource,

                            lastMessage =
                                if (applied) {
                                    "BLE microphone preferred for ${audioSourceName(audioSource)}."
                                } else {
                                    "Temporary BLE microphone preference cleared."
                                }
                        )
                    }
                }
            }
        }

    /*
     * ============================================================
     * USER SERVICE CONNECTION
     * ============================================================
     */

    private val watcherConnection =
        object :
            ServiceConnection {

            override fun onServiceConnected(
                name: ComponentName?,
                binder: IBinder?
            ) {

                watcherBinding =
                    false

                if (
                    binder ==
                    null ||
                    !binder.pingBinder()
                ) {

                    watcher =
                        null

                    VoiceAccessAutomationState.update {

                        it.copy(
                            userServiceConnected =
                                false,

                            watcherRegistered =
                                false,

                            startedWatcherRegistered =
                                false,

                            lastMessage =
                                "Shizuku AppOps UserService returned an invalid Binder."
                        )
                    }

                    return
                }

                val remote =
                    IVoiceAccessWatcher
                        .Stub
                        .asInterface(
                            binder
                        )

                watcher =
                    remote

                VoiceAccessAutomationState.update {

                    it.copy(
                        userServiceConnected =
                            true,

                        lastMessage =
                            "Shizuku AppOps UserService connected."
                    )
                }

                val uid =
                    findVoiceAccessUid()

                if (
                    uid <
                    0
                ) {

                    VoiceAccessAutomationState.update {

                        it.copy(
                            voiceAccessUidFound =
                                false,

                            watcherRegistered =
                                false,

                            startedWatcherRegistered =
                                false,

                            lastMessage =
                                "Voice Access UID was not found."
                        )
                    }

                    return
                }

                VoiceAccessAutomationState.update {

                    it.copy(
                        voiceAccessUidFound =
                            true,

                        lastMessage =
                            "Voice Access UID found. Starting microphone watchers."
                    )
                }

                /*
                 * =================================================
                 * BLE TARGET
                 * =================================================
                 *
                 * availableCommunicationDevices returns the BLE
                 * communication endpoint. AudioDeviceAttributes can
                 * use the same public device type + address with
                 * ROLE_INPUT in the privileged process.
                 */

                val ble =
                    routingManager
                        .findFirstBluetoothCommunicationDevice()

                val bleType =
                    ble
                        ?.type
                        ?: -1

                val bleAddress =
                    ble
                        ?.address
                        ?: ""

                if (
                    ble ==
                    null
                ) {

                    VoiceAccessAutomationState.update {

                        it.copy(
                            lastMessage =
                                "AppOps watcher can start, but BLE Headset / LE Audio is not currently available."
                        )
                    }
                }

                try {

                    val result =
                        remote.startWatch(
                            uid,
                            VOICE_ACCESS_PACKAGE,
                            bleType,
                            bleAddress,
                            appOpsCallback
                        )

                    val activeRegistered =
                        result.contains(
                            "Active watcher:\nYES"
                        )

                    val startedRegistered =
                        result.contains(
                            "Started watcher:\nYES"
                        )

                    val active =
                        try {

                            remote.isTargetActive()

                        } catch (_: Throwable) {

                            false
                        }

                    VoiceAccessAutomationState.update {

                        it.copy(
                            watcherRegistered =
                                activeRegistered,

                            startedWatcherRegistered =
                                startedRegistered,

                            recordAudioActive =
                                active,

                            lastMessage =
                                when {

                                    activeRegistered &&
                                        startedRegistered ->

                                        "Voice Access ACTIVE + STARTING watchers registered."

                                    activeRegistered ->

                                        "ACTIVE watcher registered; early STARTING watcher unavailable."

                                    else ->

                                        result.take(
                                            700
                                        )
                                }
                        )
                    }

                    applyVoiceAccessState(
                        active
                    )

                } catch (e: Throwable) {

                    VoiceAccessAutomationState.update {

                        it.copy(
                            watcherRegistered =
                                false,

                            startedWatcherRegistered =
                                false,

                            lastMessage =
                                "startWatch failed: ${e.javaClass.simpleName}: ${e.message}"
                        )
                    }

                    Logger.e(
                        "Could not start Voice Access AppOps watcher",
                        e
                    )
                }
            }

            override fun onServiceDisconnected(
                name: ComponentName?
            ) {

                watcher =
                    null

                watcherBinding =
                    false

                mainHandler.removeCallbacks(
                    startingTimeout
                )

                VoiceAccessAutomationState.update {

                    it.copy(
                        userServiceConnected =
                            false,

                        watcherRegistered =
                            false,

                        startedWatcherRegistered =
                            false,

                        recordAudioActive =
                            false,

                        capturePreferenceApplied =
                            false,

                        lastMessage =
                            "Shizuku AppOps UserService disconnected."
                    )
                }

                applyVoiceAccessState(
                    false
                )

                mainHandler.postDelayed(
                    {
                        bindWatcherIfPossible()
                    },
                    REBIND_DELAY_MS
                )
            }
        }

    /*
     * ============================================================
     * SHIZUKU EVENTS
     * ============================================================
     */

    private val shizukuReceivedListener =
        Shizuku.OnBinderReceivedListener {

            VoiceAccessAutomationState.update {

                it.copy(
                    shizukuReady =
                        true,

                    lastMessage =
                        "Shizuku Binder is ready."
                )
            }

            bindWatcherIfPossible()
        }

    private val shizukuDeadListener =
        Shizuku.OnBinderDeadListener {

            watcher =
                null

            watcherBinding =
                false

            mainHandler.removeCallbacks(
                startingTimeout
            )

            VoiceAccessAutomationState.update {

                it.copy(
                    shizukuReady =
                        false,

                    userServiceConnected =
                        false,

                    watcherRegistered =
                        false,

                    startedWatcherRegistered =
                        false,

                    recordAudioActive =
                        false,

                    capturePreferenceApplied =
                        false,

                    lastMessage =
                        "Shizuku stopped."
                )
            }

            applyVoiceAccessState(
                false
            )
        }

    /*
     * ============================================================
     * CREATE
     * ============================================================
     */

    override fun onCreate() {

        super.onCreate()

        routingManager =
            AudioRoutingManager(
                applicationContext
            )

        routingManager
            .startMonitoring()

        VoiceAccessAutomationState.update {

            it.copy(
                hostRunning =
                    true,

                lastMessage =
                    "Voice Access automation host created."
            )
        }

        try {

            Shizuku.addBinderReceivedListenerSticky(
                shizukuReceivedListener
            )

        } catch (e: Throwable) {

            VoiceAccessAutomationState.update {

                it.copy(
                    lastMessage =
                        "Could not register Shizuku Binder listener: ${e.message}"
                )
            }
        }

        try {

            Shizuku.addBinderDeadListener(
                shizukuDeadListener
            )

        } catch (_: Throwable) {
        }

        bindWatcherIfPossible()
    }

    /*
     * ============================================================
     * NOTIFICATION LISTENER
     * ============================================================
     */

    override fun onListenerConnected() {

        super.onListenerConnected()

        VoiceAccessAutomationState.update {

            it.copy(
                listenerConnected =
                    true,

                lastMessage =
                    "Notification-listener host CONNECTED."
            )
        }

        bindWatcherIfPossible()
    }

    override fun onListenerDisconnected() {

        VoiceAccessAutomationState.update {

            it.copy(
                listenerConnected =
                    false,

                lastMessage =
                    "Notification-listener host disconnected."
            )
        }

        voiceAccessActive =
            false

        mainHandler.removeCallbacks(
            delayedRouteOff
        )

        mainHandler.removeCallbacks(
            startingTimeout
        )

        try {

            watcher
                ?.clearCapturePreference()

        } catch (_: Throwable) {
        }

        turnRoutingOffNow()

        try {

            requestRebind(
                ComponentName(
                    this,
                    VoiceAccessMonitorService::class.java
                )
            )

        } catch (_: Throwable) {
        }

        super.onListenerDisconnected()
    }

    /*
     * ============================================================
     * FIND VOICE ACCESS UID
     * ============================================================
     */

    private fun findVoiceAccessUid():
        Int {

        return try {

            @Suppress(
                "DEPRECATION"
            )

            packageManager
                .getApplicationInfo(
                    VOICE_ACCESS_PACKAGE,
                    0
                )
                .uid

        } catch (e: Throwable) {

            Logger.e(
                "Voice Access package UID lookup failed",
                e
            )

            -1
        }
    }

    /*
     * ============================================================
     * BIND WATCHER
     * ============================================================
     */

    private fun bindWatcherIfPossible() {

        if (
            watcher !=
            null ||
            watcherBinding
        ) {
            return
        }

        val shizukuRunning =
            try {

                Shizuku.pingBinder()

            } catch (_: Throwable) {

                false
            }

        VoiceAccessAutomationState.update {

            it.copy(
                shizukuReady =
                    shizukuRunning
            )
        }

        if (
            !shizukuRunning
        ) {

            VoiceAccessAutomationState.update {

                it.copy(
                    lastMessage =
                        "Waiting for Shizuku."
                )
            }

            return
        }

        val permission =
            try {

                Shizuku.checkSelfPermission()

            } catch (_: Throwable) {

                PackageManager.PERMISSION_DENIED
            }

        if (
            permission !=
            PackageManager.PERMISSION_GRANTED
        ) {

            VoiceAccessAutomationState.update {

                it.copy(
                    lastMessage =
                        "Shizuku is running, but BTMicFix does not have Shizuku permission."
                )
            }

            return
        }

        watcherBinding =
            true

        VoiceAccessAutomationState.update {

            it.copy(
                lastMessage =
                    "Binding privileged Voice Access microphone watcher..."
            )
        }

        try {

            Shizuku.bindUserService(
                watcherArgs,
                watcherConnection
            )

        } catch (e: Throwable) {

            watcherBinding =
                false

            VoiceAccessAutomationState.update {

                it.copy(
                    lastMessage =
                        "bindUserService failed: ${e.javaClass.simpleName}: ${e.message}"
                )
            }

            Logger.e(
                "Voice Access AppOps bind failed",
                e
            )
        }
    }

    /*
     * ============================================================
     * AUTHORITATIVE ACTIVE STATE
     * ============================================================
     */

    private fun applyVoiceAccessState(
        active: Boolean
    ) {

        if (
            active
        ) {

            mainHandler.removeCallbacks(
                delayedRouteOff
            )

            mainHandler.removeCallbacks(
                startingTimeout
            )

            voiceAccessActive =
                true

            /*
             * STARTING may have already routed it.
             *
             * If not, ACTIVE is still a fallback.
             */
            turnRoutingOn()

            return
        }

        voiceAccessActive =
            false

        mainHandler.removeCallbacks(
            delayedRouteOff
        )

        if (
            routeRequested
        ) {

            mainHandler.postDelayed(
                delayedRouteOff,
                ROUTE_OFF_DEBOUNCE_MS
            )
        }
    }

    /*
     * ============================================================
     * COMMUNICATION ROUTE ON
     * ============================================================
     */

    private fun turnRoutingOn() {

        if (
            routeRequested
        ) {

            return
        }

        val ble =
            routingManager
                .findFirstBluetoothCommunicationDevice()

        if (
            ble ==
            null
        ) {

            VoiceAccessAutomationState.update {

                it.copy(
                    autoRoutingActive =
                        false,

                    lastMessage =
                        "Voice Access is listening, but BLE Headset / LE Audio is unavailable."
                )
            }

            return
        }

        routeRequested =
            true

        val result =
            routingManager
                .routeToBluetooth(
                    ble
                )

        if (
            result is
            AudioRoutingManager.RoutingState.Active
        ) {

            VoiceAccessAutomationState.update {

                it.copy(
                    autoRoutingActive =
                        true,

                    lastMessage =
                        "BLE communication route ON for Voice Access."
                )
            }

        } else {

            routeRequested =
                false

            VoiceAccessAutomationState.update {

                it.copy(
                    autoRoutingActive =
                        false,

                    lastMessage =
                        "Automatic BLE communication route failed: $result"
                )
            }
        }
    }

    /*
     * ============================================================
     * COMMUNICATION ROUTE OFF
     * ============================================================
     */

    private fun turnRoutingOffNow() {

        if (
            !routeRequested
        ) {

            VoiceAccessAutomationState.update {

                it.copy(
                    autoRoutingActive =
                        false
                )
            }

            return
        }

        if (
            voiceAccessActive
        ) {

            return
        }

        routeRequested =
            false

        try {

            routingManager
                .clearRouting()

            VoiceAccessAutomationState.update {

                it.copy(
                    autoRoutingActive =
                        false,

                    lastMessage =
                        "Voice Access stopped — BLE communication route released."
                )
            }

        } catch (e: Throwable) {

            VoiceAccessAutomationState.update {

                it.copy(
                    autoRoutingActive =
                        false,

                    lastMessage =
                        "Could not release BLE route: ${e.message}"
                )
            }
        }
    }

    /*
     * ============================================================
     * STOP WATCHER
     * ============================================================
     */

    private fun stopWatcher() {

        try {

            watcher
                ?.stopWatch()

        } catch (_: Throwable) {
        }

        watcher =
            null

        watcherBinding =
            false

        try {

            Shizuku.unbindUserService(
                watcherArgs,
                watcherConnection,
                true
            )

        } catch (_: Throwable) {
        }
    }

    /*
     * ============================================================
     * DESTROY
     * ============================================================
     */

    override fun onDestroy() {

        voiceAccessActive =
            false

        mainHandler.removeCallbacks(
            delayedRouteOff
        )

        mainHandler.removeCallbacks(
            startingTimeout
        )

        try {

            watcher
                ?.clearCapturePreference()

        } catch (_: Throwable) {
        }

        turnRoutingOffNow()

        stopWatcher()

        try {

            Shizuku.removeBinderReceivedListener(
                shizukuReceivedListener
            )

        } catch (_: Throwable) {
        }

        try {

            Shizuku.removeBinderDeadListener(
                shizukuDeadListener
            )

        } catch (_: Throwable) {
        }

        try {

            routingManager
                .stopMonitoring()

        } catch (_: Throwable) {
        }

        VoiceAccessAutomationState.update {

            it.copy(
                hostRunning =
                    false,

                listenerConnected =
                    false,

                userServiceConnected =
                    false,

                watcherRegistered =
                    false,

                startedWatcherRegistered =
                    false,

                startingEventSeen =
                    false,

                recordAudioActive =
                    false,

                capturePreferenceApplied =
                    false,

                autoRoutingActive =
                    false,

                lastMessage =
                    "Voice Access automation host stopped."
            )
        }

        super.onDestroy()
    }

    /*
     * ============================================================
     * AUDIO SOURCE LABEL
     * ============================================================
     */

    private fun audioSourceName(
        source: Int
    ): String {

        return when (
            source
        ) {

            0 ->
                "DEFAULT"

            1 ->
                "MIC"

            5 ->
                "CAMCORDER"

            6 ->
                "VOICE_RECOGNITION"

            7 ->
                "VOICE_COMMUNICATION"

            9 ->
                "UNPROCESSED"

            10 ->
                "VOICE_PERFORMANCE"

            else ->
                "AudioSource $source"
        }
    }

    companion object {

        private const val VOICE_ACCESS_PACKAGE =
            "com.google.android.apps.accessibility.voiceaccess"

        private const val ROUTE_OFF_DEBOUNCE_MS =
            1200L

        private const val START_CONFIRM_TIMEOUT_MS =
            2500L

        private const val REBIND_DELAY_MS =
            1500L

        /*
         * Bumped so Shizuku definitely loads this microphone-routing
         * implementation instead of the previous UserService.
         */
        private const val WATCHER_SERVICE_VERSION =
            6
    }
}
