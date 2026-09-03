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
 * Automatic Voice Access -> Galaxy Buds BLE microphone routing.
 *
 * This service does not parse notification text.
 * NotificationListenerService is only used as an Android-managed
 * lifecycle host.
 *
 * Voice Access RECORD_AUDIO ACTIVE:
 *     route the already-available TYPE_BLE_HEADSET communication route.
 *
 * Voice Access RECORD_AUDIO INACTIVE:
 *     wait briefly, then clear the communication route.
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

    private val delayedRouteOff =
        Runnable {
            if (!voiceAccessActive) {
                turnRoutingOffNow()
            }
        }

    private val watcherArgs:
        Shizuku.UserServiceArgs
        by lazy {
            Shizuku.UserServiceArgs(
                ComponentName(
                    BuildConfig.APPLICATION_ID,
                    VoiceAccessAppOpsService::class.java.name
                )
            )
                .daemon(false)
                .tag("btmicfix_voice_access_appops")
                .version(WATCHER_SERVICE_VERSION)
                .processNameSuffix("voice_access_appops")
                .debuggable(BuildConfig.DEBUG)
        }

    private val appOpsCallback =
        object :
            IVoiceAccessOpCallback.Stub() {

            override fun onRecordAudioActiveChanged(
                active: Boolean
            ) {
                mainHandler.post {
                    VoiceAccessAutomationState.update {
                        it.copy(
                            recordAudioActive = active,
                            lastMessage =
                                if (active) {
                                    "Voice Access RECORD_AUDIO became ACTIVE."
                                } else {
                                    "Voice Access RECORD_AUDIO became INACTIVE."
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
        }

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
                            userServiceConnected = false,
                            watcherRegistered = false,
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
                        userServiceConnected = true,
                        lastMessage =
                            "Shizuku AppOps UserService connected."
                    )
                }

                val uid =
                    findVoiceAccessUid()

                if (uid < 0) {
                    VoiceAccessAutomationState.update {
                        it.copy(
                            voiceAccessUidFound = false,
                            watcherRegistered = false,
                            lastMessage =
                                "Voice Access UID was not found."
                        )
                    }

                    return
                }

                VoiceAccessAutomationState.update {
                    it.copy(
                        voiceAccessUidFound = true,
                        lastMessage =
                            "Voice Access UID found. Starting RECORD_AUDIO watcher."
                    )
                }

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
                            watcherRegistered = registered,
                            recordAudioActive = active,
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
                            watcherRegistered = false,
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
                        userServiceConnected = false,
                        watcherRegistered = false,
                        recordAudioActive = false,
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

    private val shizukuReceivedListener =
        Shizuku.OnBinderReceivedListener {
            VoiceAccessAutomationState.update {
                it.copy(
                    shizukuReady = true,
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
                    shizukuReady = false,
                    userServiceConnected = false,
                    watcherRegistered = false,
                    recordAudioActive = false,
                    lastMessage =
                        "Shizuku stopped."
                )
            }

            applyVoiceAccessState(
                false
            )
        }

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
                hostRunning = true,
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

    override fun onListenerConnected() {
        super.onListenerConnected()

        VoiceAccessAutomationState.update {
            it.copy(
                listenerConnected = true,
                lastMessage =
                    "Notification-listener host CONNECTED."
            )
        }

        bindWatcherIfPossible()
    }

    override fun onListenerDisconnected() {
        VoiceAccessAutomationState.update {
            it.copy(
                listenerConnected = false,
                lastMessage =
                    "Notification-listener host disconnected."
            )
        }

        voiceAccessActive =
            false

        mainHandler.removeCallbacks(
            delayedRouteOff
        )

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

    private fun findVoiceAccessUid():
        Int {

        return try {
            @Suppress("DEPRECATION")
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
                shizukuReady = shizukuRunning
            )
        }

        if (!shizukuRunning) {
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
                    "Binding privileged AppOps watcher..."
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

    private fun applyVoiceAccessState(
        active: Boolean
    ) {
        if (active) {
            mainHandler.removeCallbacks(
                delayedRouteOff
            )

            if (voiceAccessActive) {
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

        if (routeRequested) {
            mainHandler.postDelayed(
                delayedRouteOff,
                ROUTE_OFF_DEBOUNCE_MS
            )
        }
    }

    private fun turnRoutingOn() {
        if (routeRequested) {
            return
        }

        val ble =
            routingManager
                .findFirstBluetoothCommunicationDevice()

        if (ble == null) {
            VoiceAccessAutomationState.update {
                it.copy(
                    autoRoutingActive = false,
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
                    autoRoutingActive = true,
                    lastMessage =
                        "AUTO ROUTE ON — Voice Access is using the Buds BLE route."
                )
            }
        } else {
            routeRequested =
                false

            VoiceAccessAutomationState.update {
                it.copy(
                    autoRoutingActive = false,
                    lastMessage =
                        "Automatic BLE route failed: $result"
                )
            }
        }
    }

    private fun turnRoutingOffNow() {
        if (!routeRequested) {
            VoiceAccessAutomationState.update {
                it.copy(
                    autoRoutingActive = false
                )
            }

            return
        }

        if (voiceAccessActive) {
            return
        }

        routeRequested =
            false

        try {
            routingManager
                .clearRouting()

            VoiceAccessAutomationState.update {
                it.copy(
                    autoRoutingActive = false,
                    lastMessage =
                        "AUTO ROUTE OFF — Voice Access stopped listening."
                )
            }

        } catch (e: Throwable) {
            VoiceAccessAutomationState.update {
                it.copy(
                    autoRoutingActive = false,
                    lastMessage =
                        "Could not release BLE route: ${e.message}"
                )
            }
        }
    }

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
                hostRunning = false,
                listenerConnected = false,
                userServiceConnected = false,
                watcherRegistered = false,
                recordAudioActive = false,
                autoRoutingActive = false,
                lastMessage =
                    "Voice Access automation host stopped."
            )
        }

        super.onDestroy()
    }

    companion object {
        private const val VOICE_ACCESS_PACKAGE =
            "com.google.android.apps.accessibility.voiceaccess"

        private const val ROUTE_OFF_DEBOUNCE_MS =
            1200L

        private const val REBIND_DELAY_MS =
            1500L

        /*
         * Bumped from the earlier broken UserService build so
         * Shizuku loads the new class implementation.
         */
        private const val WATCHER_SERVICE_VERSION =
            5
    }
}
