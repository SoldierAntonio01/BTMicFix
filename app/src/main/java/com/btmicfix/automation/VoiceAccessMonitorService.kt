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
 * Automatic Voice Access -> BLE mic routing.
 *
 *
 * IMPORTANT:
 *
 * This DOES NOT:
 *
 * - reconnect LE Audio every time
 * - refresh the LE UUID cache every time
 * - force HFP
 * - poll constantly
 *
 *
 * Your Buds already expose:
 *
 * TYPE_BLE_HEADSET / LE Audio
 *
 * so this service only toggles the existing
 * communication route.
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

    private var listenerConnected =
        false

    private var watcherBinding =
        false

    private var watcher:
        IVoiceAccessWatcher? =
        null

    /*
     * ============================================================
     * OFF DEBOUNCE
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
                 * Bumped so Shizuku destroys the old broken
                 * version and loads this one.
                 */
                .version(
                    5
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
     * APPOPS CALLBACK
     * ============================================================
     */

    private val appOpsCallback =
        object :
            IVoiceAccessOpCallback.Stub() {

            override fun onRecordAudioActiveChanged(
                active: Boolean
            ) {

                mainHandler.post {

                    VoiceAccessAutomationState.update {

                        it.copy(
                            recordAudioActive =
                                active,

                            lastMessage =
                                if (active) {
                                    "Voice Access RECORD_AUDIO became ACTIVE."
                                } else {
                                    "Voice Access RECORD_AUDIO became INACTIVE."
                                }
                        )
                    }

                    applyVoiceAccessState(
                        active
                    )
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
                    binder == null ||
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

                            lastMessage =
                                "Shizuku UserService returned an invalid Binder."
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

                /*
                 * =================================================
                 * FIND VOICE ACCESS UID
                 * =================================================
                 */

                val uid =
                    findVoiceAccessUid()

                if (
                    uid < 0
                ) {

                    VoiceAccessAutomationState.update {

                        it.copy(
                            voiceAccessUidFound =
                                false,

                            watcherRegistered =
                                false,

                            lastMessage =
                                "Voice Access UID NOT FOUND. Check the <queries> manifest entry."
                        )
                    }

                    return
                }

                VoiceAccessAutomationState.update {

                    it.copy(
                        voiceAccessUidFound =
                            true,

                        lastMessage =
                            "Voice Access UID found. Starting AppOps watcher."
                    )
                }

                /*
                 * =================================================
                 * START RECORD_AUDIO WATCH
                 * =================================================
                 */

                try {

                    val result =
                        remote.startWatch(
                            uid,
                            VOICE_ACCESS_PACKAGE,
                            appOpsCallback
                        )

                    val registered =
                        result.contains(
                            "WATCH ACTIVE"
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
                                registered,

                            recordAudioActive =
                                active,

                            lastMessage =
                                if (registered) {
                                    "AppOps watcher REGISTERED. RECORD_AUDIO=${if (active) "ACTIVE" else "INACTIVE"}."
                                } else {
                                    result.take(500)
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

                VoiceAccessAutomationState.update {

                    it.copy(
                        userServiceConnected =
                            false,

                        watcherRegistered =
                            false,

                        recordAudioActive =
                            false,

                        lastMessage =
                            "Shizuku AppOps UserService disconnected."
                    )
                }

                applyVoiceAccessState(
                    false
                )

                /*
                 * If Shizuku itself is still running,
                 * attempt to reconnect.
                 */

                mainHandler.postDelayed(
                    {
                        bindWatcherIfPossible()
                    },
                    1500L
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

            VoiceAccessAutomationState.update {

                it.copy(
                    shizukuReady =
                        false,

                    userServiceConnected =
                        false,

                    watcherRegistered =
                        false,

                    recordAudioActive =
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

        Shizuku
            .addBinderReceivedListenerSticky(
                shizukuReceivedListener
            )

        Shizuku
            .addBinderDeadListener(
                shizukuDeadListener
            )

        bindWatcherIfPossible()
    }

    /*
     * ============================================================
     * NOTIFICATION LISTENER CONNECTED
     * ============================================================
     */

    override fun onListenerConnected() {

        super.onListenerConnected()

        listenerConnected =
            true

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

    /*
     * ============================================================
     * NOTIFICATION LISTENER DISCONNECTED
     * ============================================================
     */

    override fun onListenerDisconnected() {

        listenerConnected =
            false

        VoiceAccessAutomationState.update {

            it.copy(
                listenerConnected =
                    false,

                lastMessage =
                    "Notification-listener host disconnected. Requesting rebind."
            )
        }

        voiceAccessActive =
            false

        mainHandler.removeCallbacks(
            delayedRouteOff
        )

        turnRoutingOffNow()

        /*
         * Android documents requestRebind() as safe after
         * onListenerDisconnected().
         */

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
     * BIND SHIZUKU WATCHER
     * ============================================================
     */

    private fun bindWatcherIfPossible() {

        if (
            watcher != null ||
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
                    "Binding privileged AppOps watcher…"
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
     * VOICE ACCESS STATE
     * ============================================================
     */

    private fun applyVoiceAccessState(
        active: Boolean
    ) {

        if (active) {

            mainHandler.removeCallbacks(
                delayedRouteOff
            )

            if (
                voiceAccessActive
            ) {

                return
            }

            voiceAccessActive =
                true

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

            /*
             * Voice Access can briefly release/reacquire
             * RECORD_AUDIO internally.
             *
             * Wait a moment before dropping the route.
             */

            mainHandler.postDelayed(
                delayedRouteOff,
                ROUTE_OFF_DEBOUNCE_MS
            )
        }
    }

    /*
     * ============================================================
     * ROUTE ON
     * ============================================================
     */

    private fun turnRoutingOn() {

        if (
            routeRequested
        ) {

            return
        }

        /*
         * IMPORTANT:
         *
         * We ONLY use an already-existing BLE Headset route.
         *
         * We do not reconnect LE Audio here.
         */

        val ble =
            routingManager
                .findFirstBluetoothCommunicationDevice()

        if (
            ble == null
        ) {

            VoiceAccessAutomationState.update {

                it.copy(
                    autoRoutingActive =
                        false,

                    lastMessage =
                        "Voice Access is listening, but TYPE_BLE_HEADSET is unavailable. Connect LE Audio once in BTMicFix."
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
                        "AUTO ROUTE ON — Voice Access is using the Buds BLE communication route."
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
                        "Automatic BLE route failed: $result"
                )
            }
        }
    }

    /*
     * ============================================================
     * ROUTE OFF
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
                        "AUTO ROUTE OFF — Voice Access stopped listening."
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
     * CLEANUP
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

    override fun onDestroy() {

        voiceAccessActive =
            false

        mainHandler.removeCallbacks(
            delayedRouteOff
        )

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

                recordAudioActive =
                    false,

                autoRoutingActive =
                    false,

                lastMessage =
                    "Voice Access automation host stopped."
            )
        }

        super.onDestroy()
    }

    companion object {

        private const val
            VOICE_ACCESS_PACKAGE =
            "com.google.android.apps.accessibility.voiceaccess"

        private const val
            ROUTE_OFF_DEBOUNCE_MS =
            1200L
    }
}
