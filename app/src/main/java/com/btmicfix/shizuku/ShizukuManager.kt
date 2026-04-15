package com.btmicfix.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.btmicfix.BuildConfig
import com.btmicfix.IPrivilegedService
import com.btmicfix.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * Manages Shizuku availability, permissions, and lifecycle.
 *
 * Shizuku is OPTIONAL — the app's core routing works without it.
 * When available, it enables privileged fallback operations like
 * accessing system audio APIs and resetting device classifications.
 */
class ShizukuManager {

    private val _status = MutableStateFlow(ShizukuStatus.UNKNOWN)
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    private var permissionGranted = false
    private var privilegedService: IPrivilegedService? = null

    enum class ShizukuStatus {
        UNKNOWN,
        NOT_INSTALLED,
        NOT_RUNNING,
        PERMISSION_NEEDED,
        READY,
    }

    // Callback for binder lifecycle (Shizuku starts/stops)
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Logger.i("Shizuku binder received")
        refreshStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Logger.w("Shizuku binder died")
        _status.value = ShizukuStatus.NOT_RUNNING
    }

    // Callback for permission result
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            permissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
            if (permissionGranted) {
                Logger.i("Shizuku permission granted")
                _status.value = ShizukuStatus.READY
            } else {
                Logger.w("Shizuku permission denied")
                _status.value = ShizukuStatus.PERMISSION_NEEDED
            }
        }

    /**
     * Register Shizuku listeners. Call from Activity.onCreate() or Application.onCreate().
     */
    fun initialize() {
        Logger.i("Initializing ShizukuManager")
        try {
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            refreshStatus()
        } catch (e: Exception) {
            Logger.e("Failed to initialize Shizuku listeners", e)
            _status.value = ShizukuStatus.NOT_INSTALLED
        }
    }

    /**
     * Unregister Shizuku listeners. Call from Activity.onDestroy().
     */
    fun cleanup() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (e: Exception) {
            Logger.e("Error cleaning up Shizuku listeners", e)
        }
    }

    /**
     * Refresh the current Shizuku status by probing the binder.
     */
    fun refreshStatus() {
        _status.value = try {
            if (!Shizuku.pingBinder()) {
                ShizukuStatus.NOT_RUNNING
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                permissionGranted = true
                ShizukuStatus.READY
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                ShizukuStatus.PERMISSION_NEEDED
            } else {
                ShizukuStatus.PERMISSION_NEEDED
            }
        } catch (e: Exception) {
            Logger.e("Error checking Shizuku status", e)
            ShizukuStatus.NOT_INSTALLED
        }
    }

    /**
     * Request Shizuku permission from the user.
     * The result comes back via the permissionResultListener.
     */
    fun requestPermission() {
        if (_status.value == ShizukuStatus.NOT_INSTALLED ||
            _status.value == ShizukuStatus.NOT_RUNNING
        ) {
            Logger.w("Cannot request permission — Shizuku not available")
            return
        }
        try {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (e: Exception) {
            Logger.e("Error requesting Shizuku permission", e)
        }
    }

    /**
     * Check if Shizuku is available and we have permission.
     */
    fun isAvailable(): Boolean = _status.value == ShizukuStatus.READY

    /**
     * Execute a shell command via the Shizuku UserService.
     * Returns the command output, or null on failure.
     */
    fun executeShellCommand(command: String): String? {
        if (!isAvailable()) {
            Logger.w("Cannot execute shell command — Shizuku not ready")
            return null
        }

        val service = privilegedService
        if (service == null) {
            Logger.w("PrivilegedService not bound, attempting to bind")
            bindPrivilegedService()
            return null
        }

        return try {
            val result = service.executeAudioCommand(command)
            Logger.d("Shell command succeeded: $command")
            result
        } catch (e: Exception) {
            Logger.e("Shell command exception: $command", e)
            null
        }
    }

    /**
     * Get audio system diagnostics via dumpsys.
     * Useful for debugging routing issues on specific devices.
     */
    fun getAudioDiagnostics(): String? {
        val service = privilegedService ?: return null
        return try {
            service.audioDump
        } catch (e: Exception) {
            Logger.e("Error getting audio diagnostics", e)
            null
        }
    }

    /**
     * Bind to the Shizuku UserService (PrivilegedServiceImpl).
     * The service runs in a separate process with shell privileges.
     */
    fun bindPrivilegedService() {
        if (!isAvailable()) {
            Logger.w("Cannot bind UserService — Shizuku not ready")
            return
        }

        try {
            val args = buildUserServiceArgs() ?: return
            Shizuku.bindUserService(args, userServiceConnection)
            Logger.i("Binding to PrivilegedService via Shizuku")
        } catch (e: Exception) {
            Logger.e("Failed to bind PrivilegedService", e)
        }
    }

    /**
     * Unbind from the Shizuku UserService.
     */
    fun unbindPrivilegedService() {
        try {
            val args = buildUserServiceArgs() ?: return
            Shizuku.unbindUserService(args, userServiceConnection, true)
        } catch (e: Exception) {
            // Ignore — may not be bound
        }
        privilegedService = null
    }

    private fun buildUserServiceArgs(): Shizuku.UserServiceArgs? {
        return try {
            Shizuku.UserServiceArgs(
                ComponentName(
                    BuildConfig.APPLICATION_ID,
                    PrivilegedServiceImpl::class.java.name,
                )
            )
                .daemon(false)
                .processNameSuffix("privileged")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE)
        } catch (e: Exception) {
            null
        }
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                privilegedService = IPrivilegedService.Stub.asInterface(binder)
                Logger.i("PrivilegedService connected")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            privilegedService = null
            Logger.w("PrivilegedService disconnected")
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1337
    }
}
