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
import com.btmicfix.bluetooth.LeAudioCacheRefresher
import com.btmicfix.shizuku.LeAudioShizukuBridge
import com.btmicfix.shizuku.VoiceAccessAppOpsService
import com.btmicfix.util.Logger
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Voice Access -> BTMicFix automation.
 *
 *
 * NORMAL STATE
 *
 * Voice Access stopped:
 *
 * BTMicFix = Idle
 * communication route = OFF
 *
 *
 * VOICE ACCESS LISTENING
 *
 * Android AppOps says:
 *
 * RECORD_AUDIO = ACTIVE
 *
 *      ↓
 *
 * BTMicFix routes TYPE_BLE_HEADSET
 *
 *
 * VOICE ACCESS PAUSED
 *
 * RECORD_AUDIO = INACTIVE
 *
 *      ↓
 *
 * BTMicFix releases communication route
 *
 *
 * IMPORTANT:
 *
 * This service DOES NOT detect Voice Access by notification text.
 *
 * NotificationListenerService is used only as a lightweight
 * Android-managed lifecycle host.
 *
 * Actual detection occurs inside our privileged Shizuku
 * AppOps watcher.
 */
class VoiceAccessMonitorService :
    NotificationListenerService() {

    /*
     * ============================================================
     * AUDIO ROUTING
     * ============================================================
     */

    private lateinit var routingManager:
        AudioRoutingManager

    /*
     * ============================================================
     * MAIN THREAD
     * ============================================================
     */

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    /*
     * ============================================================
     * BACKGROUND BLUETOOTH WORK
     * ============================================================
     */

    private val worker =
        Executors
            .newSingleThreadExecutor()

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
    private var voiceAccessActive =
        false

    @Volatile
    private var routeRequested =
        false

    private var watcher:
        IVoiceAccessWatcher? =
        null

    private var watcherBinding =
        false

    /*
     * ============================================================
     * OFF DEBOUNCE
     * ============================================================
     *
     * If Voice Access briefly releases RECORD_AUDIO internally
     * between recognition cycles, don't immediately tear down
     * the route.
     *
     * A real "Stop listening" state remains inactive and this
     * releases the route after 1.2 seconds.
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
     * SHIZUKU USER SERVICE ARGS
     * ============================================================
     */

    private val watcherArgs:
        Shizuku.UserServiceArgs
        by lazy {

            Shizuku.UserServiceArgs(
                ComponentName(
                    applicationContext,
                    VoiceAccessAppOpsService::class.java
                )
            )
                .daemon(
                    false
                )
                .tag(
                    "btmicfix_voice_access_appops"
                )
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

            override fun onRecordAudioActiveChanged(
                active: Boolean
            ) {

                /*
                 * Binder callback may arrive on a Binder thread.
                 */

                mainHandler.post {

                    Logger.i(
                        "Voice Access RECORD_AUDIO active = $active"
                    )

                    applyVoiceAccessState(
                        active
                    )
                }
            }
        }

    /*
     * ============================================================
     * SHIZUKU SERVICE CONNECTION
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

                    Logger.w(
                        "Voice Access AppOps watcher returned invalid Binder"
                    )

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

                val voiceAccessUid =
                    findVoiceAccessUid()

                if (
                    voiceAccessUid <
                    0
                ) {

                    Logger.w(
                        "Voice Access package UID could not be found"
                    )

                    return
                }

                try {

                    val result =
                        remote.startWatch(
                            voiceAccessUid,
                            VOICE_ACCESS_PACKAGE,
                            appOpsCallback
                        )

                    Logger.i(
                        result
                    )

                    /*
                     * startWatch already sends the initial callback,
                     * but query again as a safety check.
                     */

                    val active =
                        remote.isTargetActive()

                    applyVoiceAccessState(
                        active
                    )

                } catch (
                    e: Throwable
                ) {

                    Logger.e(
                        "Could not start Voice Access AppOps watcher",
                        e
                    )
                }
            }

            override fun onServiceDisconnected(
                name: ComponentName?
            ) {

                Logger.w(
                    "Voice Access AppOps UserService disconnected"
                )

                watcher =
                    null

                watcherBinding =
                    false

                applyVoiceAccessState(
                    false
                )
            }
        }

    /*
     * ============================================================
     * SHIZUKU BINDER EVENTS
     * ============================================================
     */

    private val shizukuReceivedListener =
        Shizuku.OnBinderReceivedListener {

            Logger.i(
                "Shizuku Binder available for Voice Access automation"
            )

            bindWatcherIfPossible()
        }

    private val shizukuDeadListener =
        Shizuku.OnBinderDeadListener {

            Logger.w(
                "Shizuku Binder died"
            )

            watcher =
                null

            watcherBinding =
                false

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

        Logger.i(
            "VoiceAccessMonitorService created"
        )

        routingManager =
            AudioRoutingManager(
                applicationContext
            )

        /*
         * Event-driven device monitoring.
         *
         * This does NOT turn routing on.
         */

        routingManager
            .startMonitoring()

        /*
         * Listen for Shizuku becoming available/restarting.
         */

        Shizuku
            .addBinderReceivedListenerSticky(
                shizukuReceivedListener
            )

        Shizuku
            .addBinderDeadListener(
                shizukuDeadListener
            )
    }

    /*
     * ============================================================
     * ANDROID CONNECTS NOTIFICATION LISTENER
     * ============================================================
     */

    override fun onListenerConnected() {

        super.onListenerConnected()

        Logger.i(
            "Voice Access automation lifecycle host connected"
        )

        bindWatcherIfPossible()
    }

    /*
     * ============================================================
     * BIND PRIVILEGED WATCHER
     * ============================================================
     */

    private fun bindWatcherIfPossible() {

        if (
            watcher !=
            null
        ) {

            return
        }

        if (
            watcherBinding
        ) {

            return
        }

        val shizukuRunning =
            try {

                Shizuku.pingBinder()

            } catch (
                _: Throwable
            ) {

                false
            }

        if (
            !shizukuRunning
        ) {

            Logger.w(
                "Voice Access automation waiting for Shizuku"
            )

            return
        }

        val permission =
            try {

                Shizuku.checkSelfPermission()

            } catch (
                _: Throwable
            ) {

                PackageManager.PERMISSION_DENIED
            }

        if (
            permission !=
            PackageManager.PERMISSION_GRANTED
        ) {

            Logger.w(
                "Voice Access automation waiting for Shizuku permission"
            )

            return
        }

        watcherBinding =
            true

        try {

            Shizuku.bindUserService(
                watcherArgs,
                watcherConnection
            )

        } catch (
            e: Throwable
        ) {

            watcherBinding =
                false

            Logger.e(
                "Could not bind Voice Access AppOps UserService",
                e
            )
        }
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

        } catch (
            e: Throwable
        ) {

            Logger.e(
                "Voice Access package not found",
                e
            )

            -1
        }
    }

    /*
     * ============================================================
     * APPLY VOICE ACCESS STATE
     * ============================================================
     */

    private fun applyVoiceAccessState(
        active: Boolean
    ) {

        if (
            active
        ) {

            /*
             * Cancel any pending OFF operation.
             */

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

            Logger.i(
                "✓ Voice Access listening -> BLE mic ON"
            )

            turnRoutingOn()

        } else {

            if (
                !voiceAccessActive &&
                !routeRequested
            ) {

                return
            }

            voiceAccessActive =
                false

            /*
             * Do not immediately drop routing.
             *
             * This protects against tiny AppOps gaps.
             */

            mainHandler.removeCallbacks(
                delayedRouteOff
            )

            mainHandler.postDelayed(
                delayedRouteOff,
                VOICE_ACCESS_OFF_DEBOUNCE_MS
            )

            Logger.i(
                "Voice Access RECORD_AUDIO inactive -> " +
                    "waiting briefly before BLE mic OFF"
            )
        }
    }

    /*
     * ============================================================
     * ROUTING ON
     * ============================================================
     */

    private fun turnRoutingOn() {

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
         * This should be the normal path on your Fold6.
         *
         * Your screenshot already shows:
         *
         * Antonio's Buds4 Pro
         * BLE Headset / LE Audio
         */

        val bleDevice =
            routingManager
                .findFirstBluetoothCommunicationDevice()

        if (
            bleDevice !=
            null
        ) {

            Logger.i(
                "Voice Access -> fast BLE routing"
            )

            val result =
                routingManager
                    .routeToBluetooth(
                        bleDevice
                    )

            if (
                result is
                AudioRoutingManager
                    .RoutingState
                    .Failed
            ) {

                routeRequested =
                    false

                Logger.w(
                    "Fast automatic BLE route failed: " +
                        result.reason
                )
            }

            return
        }

        /*
         * ========================================================
         * SLOW RECOVERY PATH
         * ========================================================
         *
         * Only if TYPE_BLE_HEADSET disappeared.
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
     * SLOW PATH
     * ============================================================
     */

    private fun activateLeAudioSlowPath() {

        if (
            !shouldRemainActive()
        ) {

            routeRequested =
                false

            return
        }

        /*
         * Find Buds from currently visible Bluetooth audio devices.
         */

        val buds =
            routingManager
                .availableDevices
                .value
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
                "Automatic mode could not identify Buds"
            )

            routeRequested =
                false

            return
        }

        val preferredName =
            buds.name

        /*
         * --------------------------------------------------------
         * LE AUDIO CACHE
         * --------------------------------------------------------
         */

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
                "Voice Access slow path: LE cache not ready"
            )

            routeRequested =
                false

            return
        }

        /*
         * --------------------------------------------------------
         * LE AUDIO PROFILE
         * --------------------------------------------------------
         */

        val connect =
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
            connect.contains(
                "ACCEPTED - LE AUDIO CONNECTED"
            ) ||
                connect.contains(
                    "ACCEPTED - ALREADY CONNECTED"
                )

        if (
            !connected
        ) {

            Logger.w(
                "Voice Access slow path: LE Audio did not connect"
            )

            routeRequested =
                false

            return
        }

        /*
         * Brief settle time for TYPE_BLE_HEADSET.
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

        val result =
            routingManager
                .routeToFirstAvailableBluetooth()

        if (
            result is
            AudioRoutingManager
                .RoutingState
                .Failed
        ) {

            Logger.w(
                "Final automatic BLE route failed: " +
                    result.reason
            )

            routeRequested =
                false
        }
    }

    /*
     * ============================================================
     * ROUTING OFF
     * ============================================================
     */

    private fun turnRoutingOffNow() {

        if (
            !routeRequested
        ) {

            return
        }

        /*
         * If Voice Access came back while our debounce timer
         * was waiting, leave the route alone.
         */

        if (
            voiceAccessActive
        ) {

            return
        }

        routeRequested =
            false

        Logger.i(
            "✓ Voice Access stopped -> BLE mic route OFF"
        )

        /*
         * This releases only AudioManager communication routing.
         *
         * It does NOT intentionally:
         *
         * - unpair Buds
         * - remove LE cache
         * - disable Bluetooth
         * - disconnect LE Audio profile
         */

        try {

            routingManager
                .clearRouting()

        } catch (
            e: Throwable
        ) {

            Logger.e(
                "Could not clear automatic BLE route",
                e
            )
        }
    }

    /*
     * ============================================================
     * SLOW PATH GUARD
     * ============================================================
     */

    private fun shouldRemainActive():
        Boolean {

        return routeRequested &&
            voiceAccessActive
    }

    /*
     * ============================================================
     * CLEANUP WATCHER
     * ============================================================
     */

    private fun stopWatcher(
        removeRemoteService: Boolean
    ) {

        try {

            watcher
                ?.stopWatch()

        } catch (
            _: Throwable
        ) {
        }

        watcher =
            null

        watcherBinding =
            false

        try {

            Shizuku.unbindUserService(
                watcherArgs,
                watcherConnection,
                removeRemoteService
            )

        } catch (
            _: Throwable
        ) {
        }
    }

    /*
     * ============================================================
     * NOTIFICATION LISTENER DISCONNECTED
     * ============================================================
     */

    override fun onListenerDisconnected() {

        Logger.w(
            "Voice Access automation lifecycle host disconnected"
        )

        voiceAccessActive =
            false

        mainHandler.removeCallbacks(
            delayedRouteOff
        )

        turnRoutingOffNow()

        stopWatcher(
            removeRemoteService =
                true
        )

        super.onListenerDisconnected()
    }

    /*
     * ============================================================
     * DESTROY
     * ============================================================
     */

    override fun onDestroy() {

        Logger.i(
            "VoiceAccessMonitorService destroyed"
        )

        voiceAccessActive =
            false

        mainHandler.removeCallbacks(
            delayedRouteOff
        )

        turnRoutingOffNow()

        stopWatcher(
            removeRemoteService =
                true
        )

        try {

            Shizuku.removeBinderReceivedListener(
                shizukuReceivedListener
            )

        } catch (
            _: Throwable
        ) {
        }

        try {

            Shizuku.removeBinderDeadListener(
                shizukuDeadListener
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

    companion object {

        /*
         * Google's Voice Access package.
         */
        private const val
            VOICE_ACCESS_PACKAGE =
            "com.google.android.apps.accessibility.voiceaccess"

        /*
         * Give tiny internal recognition gaps time to recover
         * before releasing BLE communication routing.
         */
        private const val
            VOICE_ACCESS_OFF_DEBOUNCE_MS =
            1200L

        private const val
            BLE_SETTLE_MS =
            500L

        /*
         * Bump if we later modify VoiceAccessAppOpsService.
         */
        private const val
            WATCHER_SERVICE_VERSION =
            1
    }
    }
