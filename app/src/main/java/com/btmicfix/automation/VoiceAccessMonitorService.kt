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
 * BTMicFix Voice Access automation.
 *
 *
 * DESIRED BEHAVIOR
 * =================
 *
 * Voice Access stopped:
 *
 *      BTMicFix = Idle
 *      BLE communication route = OFF
 *
 *
 * Voice Access starts listening:
 *
 *      Android RECORD_AUDIO AppOp = ACTIVE
 *
 *              ↓
 *
 *      Shizuku AppOps watcher callback
 *
 *              ↓
 *
 *      BTMicFix routes TYPE_BLE_HEADSET
 *
 *
 * Voice Access stops listening:
 *
 *      RECORD_AUDIO = INACTIVE
 *
 *              ↓
 *
 *      short debounce
 *
 *              ↓
 *
 *      clearCommunicationDevice()
 *
 *              ↓
 *
 *      BTMicFix = Idle
 *
 *
 * IMPORTANT:
 *
 * This does NOT continuously poll Voice Access.
 *
 * Voice Access state comes from the privileged
 * VoiceAccessAppOpsService running through Shizuku.
 *
 * NotificationListenerService is only being used as an
 * Android-managed lifecycle host for the background automation.
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
     * DELAYED ROUTE OFF
     * ============================================================
     *
     * Voice recognition can occasionally produce tiny internal
     * RECORD_AUDIO gaps.
     *
     * We don't want one tiny gap to shut the Buds mic off.
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
     *
     * IMPORTANT:
     *
     * Use the package-name/class-name ComponentName constructor.
     *
     * This follows the same pattern used by our other
     * Shizuku UserService.
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
                .processNameSuffix(
                    "voice_access_appops"
                )
                .debuggable(
                    BuildConfig.DEBUG
                )
                .version(
                    WATCHER_SERVICE_VERSION
                )
        }

    /*
     * ============================================================
     * APPOPS CALLBACK FROM SHIZUKU PROCESS
     * ============================================================
     */

    private val appOpsCallback =
        object :
            IVoiceAccessOpCallback.Stub() {

            override fun onRecordAudioActiveChanged(
                active: Boolean
            ) {

                /*
                 * This callback comes from Binder.
                 *
                 * Send state handling onto the main thread.
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
     * SHIZUKU USER SERVICE CONNECTION
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

                /*
                 * Validate Binder.
                 */

                if (
                    binder == null ||
                    !binder.pingBinder()
                ) {

                    Logger.w(
                        "Voice Access AppOps watcher returned invalid Binder"
                    )

                    watcher =
                        null

                    return
                }

                /*
                 * Convert Binder into generated AIDL interface.
                 */

                val remote =
                    IVoiceAccessWatcher
                        .Stub
                        .asInterface(
                            binder
                        )

                watcher =
                    remote

                /*
                 * Find Google's Voice Access UID.
                 */

                val voiceAccessUid =
                    findVoiceAccessUid()

                if (
                    voiceAccessUid < 0
                ) {

                    Logger.w(
                        "Voice Access package UID could not be found"
                    )

                    return
                }

                /*
                 * Start watching Voice Access RECORD_AUDIO.
                 */

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
                     * startWatch() should already send the initial
                     * callback, but query once as a safety check.
                     */

                    val currentlyActive =
                        remote.isTargetActive()

                    applyVoiceAccessState(
                        currentlyActive
                    )

                } catch (e: Throwable) {

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

                /*
                 * Fail safe:
                 *
                 * If the privileged watcher dies, don't leave the
                 * Buds communication route permanently active.
                 */

                applyVoiceAccessState(
                    false
                )
            }
        }

    /*
     * ============================================================
     * SHIZUKU BINDER RECEIVED
     * ============================================================
     */

    private val shizukuReceivedListener =
        Shizuku.OnBinderReceivedListener {

            Logger.i(
                "Shizuku Binder available for Voice Access automation"
            )

            bindWatcherIfPossible()
        }

    /*
     * ============================================================
     * SHIZUKU BINDER DEAD
     * ============================================================
     */

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
         * This only watches audio-device changes.
         *
         * It DOES NOT automatically route the Buds.
         */

        routingManager
            .startMonitoring()

        /*
         * Listen for Shizuku startup/restart.
         */

        try {

            Shizuku
                .addBinderReceivedListener(
                    shizukuReceivedListener
                )

        } catch (e: Throwable) {

            Logger.w(
                "Could not add Shizuku binder listener: ${e.message}"
            )
        }

        try {

            Shizuku
                .addBinderDeadListener(
                    shizukuDeadListener
                )

        } catch (e: Throwable) {

            Logger.w(
                "Could not add Shizuku death listener: ${e.message}"
            )
        }

        /*
         * Shizuku may already be running before this listener
         * was registered.
         */

        bindWatcherIfPossible()
    }

    /*
     * ============================================================
     * NOTIFICATION LISTENER CONNECTED
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
     * BIND PRIVILEGED APPOPS WATCHER
     * ============================================================
     */

    private fun bindWatcherIfPossible() {

        /*
         * Already connected.
         */

        if (
            watcher != null
        ) {

            return
        }

        /*
         * Already trying.
         */

        if (
            watcherBinding
        ) {

            return
        }

        /*
         * Is Shizuku alive?
         */

        val shizukuRunning =
            try {

                Shizuku.pingBinder()

            } catch (_: Throwable) {

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

        /*
         * Does BTMicFix have Shizuku permission?
         */

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

            Logger.w(
                "Voice Access automation waiting for Shizuku permission"
            )

            return
        }

        watcherBinding =
            true

        /*
         * Bind the privileged watcher.
         */

        try {

            Shizuku.bindUserService(
                watcherArgs,
                watcherConnection
            )

            Logger.i(
                "Binding Voice Access AppOps watcher"
            )

        } catch (e: Throwable) {

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

        } catch (e: Throwable) {

            Logger.e(
                "Voice Access package not found",
                e
            )

            -1
        }
    }

    /*
     * ============================================================
     * APPLY VOICE ACCESS APPOPS STATE
     * ============================================================
     */

    private fun applyVoiceAccessState(
        active: Boolean
    ) {

        /*
         * ========================================================
         * VOICE ACCESS ACTIVE
         * ========================================================
         */

        if (active) {

            /*
             * Cancel pending OFF.
             */

            mainHandler
                .removeCallbacks(
                    delayedRouteOff
                )

            /*
             * Already active.
             */

            if (
                voiceAccessActive
            ) {

                return
            }

            voiceAccessActive =
                true

            Logger.i(
                "Voice Access listening -> BLE mic ON"
            )

            turnRoutingOn()

            return
        }

        /*
         * ========================================================
         * VOICE ACCESS INACTIVE
         * ========================================================
         */

        if (
            !voiceAccessActive &&
            !routeRequested
        ) {

            return
        }

        voiceAccessActive =
            false

        /*
         * Cancel an older OFF timer if one exists.
         */

        mainHandler
            .removeCallbacks(
                delayedRouteOff
            )

        /*
         * Wait briefly.
         *
         * If Voice Access becomes active again during this window,
         * applyVoiceAccessState(true) cancels this runnable.
         */

        mainHandler
            .postDelayed(
                delayedRouteOff,
                VOICE_ACCESS_OFF_DEBOUNCE_MS
            )

        Logger.i(
            "Voice Access RECORD_AUDIO inactive -> " +
                "waiting before BLE mic OFF"
        )
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
         * This should normally be used on your Fold6 because the
         * BLE Headset / LE Audio endpoint already exists.
         */

        val existingBle =
            routingManager
                .findFirstBluetoothCommunicationDevice()

        if (
            existingBle != null
        ) {

            Logger.i(
                "Voice Access -> fast BLE routing"
            )

            val result =
                routingManager
                    .routeToBluetooth(
                        existingBle
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

            } else {

                Logger.i(
                    "Fast automatic BLE route active"
                )
            }

            return
        }

        /*
         * ========================================================
         * SLOW RECOVERY PATH
         * ========================================================
         *
         * Only used if TYPE_BLE_HEADSET disappeared.
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
         * Voice Access could have stopped before the worker starts.
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
            buds == null
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
         * ========================================================
         * CHECK/REFRESH LE AUDIO UUID CACHE
         * ========================================================
         */

        Logger.i(
            "Voice Access slow path checking LE Audio cache"
        )

        val refresh =
            LeAudioCacheRefresher
                .refresh(
                    context =
                        applicationContext,

                    preferredDeviceName =
                        preferredName
                )

        /*
         * Voice Access stopped while cache work was happening.
         */

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
         * ========================================================
         * CONNECT LE AUDIO PROFILE
         * ========================================================
         */

        Logger.i(
            "Voice Access slow path connecting LE Audio"
        )

        val connectResult =
            LeAudioShizukuBridge
                .forceLeAudio(
                    context =
                        applicationContext,

                    preferredDeviceName =
                        preferredName
                )

        /*
         * Voice Access stopped during connection.
         */

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
                "Voice Access slow path: LE Audio did not connect"
            )

            routeRequested =
                false

            return
        }

        /*
         * Give Android a short time to expose TYPE_BLE_HEADSET.
         */

        try {

            Thread.sleep(
                BLE_SETTLE_MS
            )

        } catch (_: InterruptedException) {
        }

        /*
         * Voice Access may have stopped during settle time.
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
         * FINAL BLE ROUTE
         * ========================================================
         */

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

        } else {

            Logger.i(
                "Voice Access automatic BLE route active"
            )
        }
    }

    /*
     * ============================================================
     * ROUTING OFF
     * ============================================================
     */

    private fun turnRoutingOffNow() {

        /*
         * Already off.
         */

        if (
            !routeRequested
        ) {

            return
        }

        /*
         * Voice Access became active again while the delay
         * was running.
         */

        if (
            voiceAccessActive
        ) {

            return
        }

        routeRequested =
            false

        Logger.i(
            "Voice Access stopped -> BLE mic route OFF"
        )

        /*
         * IMPORTANT:
         *
         * This releases:
         *
         * - communication device
         * - MODE_IN_COMMUNICATION
         *
         * It does NOT intentionally:
         *
         * - unpair Buds
         * - clear LE Audio UUID cache
         * - turn Bluetooth off
         * - remove LE Audio capability
         */

        try {

            routingManager
                .clearRouting()

        } catch (e: Throwable) {

            Logger.e(
                "Could not clear automatic BLE route",
                e
            )
        }
    }

    /*
     * ============================================================
     * SHOULD SLOW PATH CONTINUE?
     * ============================================================
     */

    private fun shouldRemainActive():
        Boolean {

        return (
            routeRequested &&
            voiceAccessActive
        )
    }

    /*
     * ============================================================
     * STOP PRIVILEGED WATCHER
     * ============================================================
     */

    private fun stopWatcher(
        removeRemoteService: Boolean
    ) {

        /*
         * Ask remote side to stop AppOps callback.
         */

        try {

            watcher
                ?.stopWatch()

        } catch (_: Throwable) {
        }

        watcher =
            null

        watcherBinding =
            false

        /*
         * Unbind Shizuku UserService.
         */

        try {

            Shizuku
                .unbindUserService(
                    watcherArgs,
                    watcherConnection,
                    removeRemoteService
                )

        } catch (_: Throwable) {
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

        /*
         * Fail safe.
         */

        voiceAccessActive =
            false

        mainHandler
            .removeCallbacks(
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

        mainHandler
            .removeCallbacks(
                delayedRouteOff
            )

        /*
         * Don't leave microphone routing active if this service dies.
         */

        turnRoutingOffNow()

        /*
         * Stop Shizuku AppOps watcher.
         */

        stopWatcher(
            removeRemoteService =
                true
        )

        /*
         * Remove Shizuku listeners.
         */

        try {

            Shizuku
                .removeBinderReceivedListener(
                    shizukuReceivedListener
                )

        } catch (_: Throwable) {
        }

        try {

            Shizuku
                .removeBinderDeadListener(
                    shizukuDeadListener
                )

        } catch (_: Throwable) {
        }

        /*
         * Stop audio callbacks.
         */

        try {

            routingManager
                .stopMonitoring()

        } catch (_: Throwable) {
        }

        /*
         * Kill worker.
         */

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
         * Google Voice Access.
         */

        private const val
            VOICE_ACCESS_PACKAGE =
            "com.google.android.apps.accessibility.voiceaccess"

        /*
         * Small delay prevents tiny Voice Access recognition gaps
         * from constantly flipping the Buds communication route.
         */

        private const val
            VOICE_ACCESS_OFF_DEBOUNCE_MS =
            1200L

        /*
         * Wait for TYPE_BLE_HEADSET after recovering LE Audio.
         */

        private const val
            BLE_SETTLE_MS =
            500L

        /*
         * Shizuku UserService version.
         */

        private const val
            WATCHER_SERVICE_VERSION =
            1
    }
    }
