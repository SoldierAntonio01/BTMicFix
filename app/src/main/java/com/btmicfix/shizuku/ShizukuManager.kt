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
 * Manages Shizuku availability, permission, and UserService lifecycle.
 *
 * This version includes support for the experimental
 * Force LE Audio privileged Shizuku service.
 */
class ShizukuManager {

    private val _status =
        MutableStateFlow(
            ShizukuStatus.UNKNOWN
        )

    val status:
        StateFlow<ShizukuStatus> =
        _status.asStateFlow()

    private var permissionGranted =
        false

    private var privilegedService:
        IPrivilegedService? = null

    enum class ShizukuStatus {
        UNKNOWN,
        NOT_INSTALLED,
        NOT_RUNNING,
        PERMISSION_NEEDED,
        READY
    }

    /*
     * ============================================================
     * SHIZUKU BINDER RECEIVED
     * ============================================================
     */

    private val binderReceivedListener =
        Shizuku.OnBinderReceivedListener {

            Logger.i(
                "Shizuku binder received"
            )

            refreshStatus()

            if (
                _status.value ==
                ShizukuStatus.READY
            ) {

                bindPrivilegedService()
            }
        }

    /*
     * ============================================================
     * SHIZUKU BINDER DEAD
     * ============================================================
     */

    private val binderDeadListener =
        Shizuku.OnBinderDeadListener {

            Logger.w(
                "Shizuku binder died"
            )

            privilegedService =
                null

            _status.value =
                ShizukuStatus.NOT_RUNNING
        }

    /*
     * ============================================================
     * SHIZUKU PERMISSION RESULT
     * ============================================================
     */

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener {
                _,
                grantResult ->

            permissionGranted =
                grantResult ==
                    PackageManager.PERMISSION_GRANTED

            if (permissionGranted) {

                Logger.i(
                    "Shizuku permission granted"
                )

                _status.value =
                    ShizukuStatus.READY

                bindPrivilegedService()

            } else {

                Logger.w(
                    "Shizuku permission denied"
                )

                _status.value =
                    ShizukuStatus.PERMISSION_NEEDED
            }
        }

    /*
     * ============================================================
     * INITIALIZE
     * ============================================================
     */

    fun initialize() {

        Logger.i(
            "Initializing ShizukuManager"
        )

        try {

            Shizuku.addBinderReceivedListener(
                binderReceivedListener
            )

            Shizuku.addBinderDeadListener(
                binderDeadListener
            )

            Shizuku.addRequestPermissionResultListener(
                permissionResultListener
            )

            refreshStatus()

            if (
                _status.value ==
                ShizukuStatus.READY
            ) {

                bindPrivilegedService()
            }

        } catch (e: Exception) {

            Logger.e(
                "Failed to initialize Shizuku",
                e
            )

            _status.value =
                ShizukuStatus.NOT_INSTALLED
        }
    }

    /*
     * ============================================================
     * CLEANUP
     * ============================================================
     */

    fun cleanup() {

        try {

            Shizuku.removeBinderReceivedListener(
                binderReceivedListener
            )

            Shizuku.removeBinderDeadListener(
                binderDeadListener
            )

            Shizuku.removeRequestPermissionResultListener(
                permissionResultListener
            )

        } catch (e: Exception) {

            Logger.e(
                "Shizuku cleanup error",
                e
            )
        }
    }

    /*
     * ============================================================
     * REFRESH STATUS
     * ============================================================
     */

    fun refreshStatus() {

        _status.value =
            try {

                if (
                    !Shizuku.pingBinder()
                ) {

                    ShizukuStatus.NOT_RUNNING

                } else if (
                    Shizuku.checkSelfPermission() ==
                    PackageManager.PERMISSION_GRANTED
                ) {

                    permissionGranted =
                        true

                    ShizukuStatus.READY

                } else {

                    ShizukuStatus.PERMISSION_NEEDED
                }

            } catch (
                e: Exception
            ) {

                Logger.e(
                    "Error checking Shizuku",
                    e
                )

                ShizukuStatus.NOT_INSTALLED
            }
    }

    /*
     * ============================================================
     * REQUEST SHIZUKU PERMISSION
     * ============================================================
     */

    fun requestPermission() {

        if (
            _status.value ==
            ShizukuStatus.NOT_INSTALLED ||

            _status.value ==
            ShizukuStatus.NOT_RUNNING
        ) {

            Logger.w(
                "Shizuku not available"
            )

            return
        }

        try {

            Shizuku.requestPermission(
                PERMISSION_REQUEST_CODE
            )

        } catch (e: Exception) {

            Logger.e(
                "Shizuku permission request failed",
                e
            )
        }
    }

    /*
     * ============================================================
     * AVAILABILITY
     * ============================================================
     */

    fun isAvailable():
        Boolean {

        return (
            _status.value ==
                ShizukuStatus.READY
            )
    }

    /*
     * ============================================================
     * PRIVILEGED SERVICE STATUS
     * ============================================================
     */

    fun isPrivilegedServiceConnected():
        Boolean {

        val service =
            privilegedService
                ?: return false

        return try {

            service
                .asBinder()
                .pingBinder()

        } catch (_: Exception) {

            false
        }
    }

    /*
     * ============================================================
     * FORCE LE AUDIO
     * ============================================================
     */

    fun forceLeAudio(
        address: String
    ): String {

        if (!isAvailable()) {

            return """
                ===== BTMicFix FORCE LE AUDIO =====
                FAILED

                Shizuku is not ready.

                Open Shizuku and make sure
                it says Running.
                ===================================
            """.trimIndent()
        }

        val service =
            privilegedService

        if (service == null) {

            Logger.w(
                "Privileged service not connected. Binding now."
            )

            bindPrivilegedService()

            return """
                ===== BTMicFix FORCE LE AUDIO =====
                STARTING SHIZUKU SERVICE

                The privileged service is starting.

                Wait about one second and press
                Force LE Audio again.
                ===================================
            """.trimIndent()
        }

        return try {

            Logger.i(
                "Calling privileged forceLeAudio($address)"
            )

            service.forceLeAudio(
                address
            )

        } catch (
            e: Exception
        ) {

            Logger.e(
                "forceLeAudio binder call failed",
                e
            )

            """
                ===== BTMicFix FORCE LE AUDIO =====
                FAILED

                ${e.javaClass.simpleName}

                ${e.message}
                ===================================
            """.trimIndent()
        }
    }

    /*
     * ============================================================
     * EXECUTE PRIVILEGED SHELL COMMAND
     * ============================================================
     */

    fun executeShellCommand(
        command: String
    ): String? {

        if (!isAvailable()) {

            Logger.w(
                "Shizuku not ready"
            )

            return null
        }

        val service =
            privilegedService

        if (service == null) {

            bindPrivilegedService()

            return null
        }

        return try {

            service.executeAudioCommand(
                command
            )

        } catch (e: Exception) {

            Logger.e(
                "Shell command failed",
                e
            )

            null
        }
    }

    /*
     * ============================================================
     * GET AUDIO DIAGNOSTICS
     * ============================================================
     */

    fun getAudioDiagnostics():
        String? {

        val service =
            privilegedService
                ?: return null

        return try {

            service.audioDump

        } catch (e: Exception) {

            Logger.e(
                "Audio diagnostics failed",
                e
            )

            null
        }
    }

    /*
     * ============================================================
     * BIND PRIVILEGED USER SERVICE
     * ============================================================
     */

    fun bindPrivilegedService() {

        if (!isAvailable()) {

            Logger.w(
                "Cannot bind UserService — Shizuku not ready"
            )

            return
        }

        /*
         * Avoid duplicate service bindings.
         */
        if (
            isPrivilegedServiceConnected()
        ) {

            Logger.d(
                "PrivilegedService already connected"
            )

            return
        }

        try {

            val args =
                buildUserServiceArgs()
                    ?: return

            Shizuku.bindUserService(
                args,
                userServiceConnection
            )

            Logger.i(
                "Binding PrivilegedService via Shizuku"
            )

        } catch (
            e: Exception
        ) {

            Logger.e(
                "Could not bind PrivilegedService",
                e
            )
        }
    }

    /*
     * ============================================================
     * UNBIND PRIVILEGED USER SERVICE
     * ============================================================
     */

    fun unbindPrivilegedService() {

        try {

            val args =
                buildUserServiceArgs()
                    ?: return

            Shizuku.unbindUserService(
                args,
                userServiceConnection,
                true
            )

        } catch (
            e: Exception
        ) {

            Logger.e(
                "Could not unbind PrivilegedService",
                e
            )
        }

        privilegedService =
            null
    }

    /*
     * ============================================================
     * BUILD SHIZUKU USER SERVICE ARGUMENTS
     * ============================================================
     */

    private fun buildUserServiceArgs():
        Shizuku.UserServiceArgs? {

        return try {

            Shizuku.UserServiceArgs(
                ComponentName(
                    BuildConfig.APPLICATION_ID,
                    PrivilegedServiceImpl::class.java.name
                )
            )
                .daemon(
                    false
                )
                .processNameSuffix(
                    "privileged"
                )
                .debuggable(
                    BuildConfig.DEBUG
                )
                /*
                 * IMPORTANT
                 *
                 * Version 3 forces Shizuku to kill the previous
                 * UserService and load our new Bluetooth adapter fix.
                 */
                .version(
                    PRIVILEGED_SERVICE_VERSION
                )

        } catch (
            e: Exception
        ) {

            Logger.e(
                "Could not build UserServiceArgs",
                e
            )

            null
        }
    }

    /*
     * ============================================================
     * SHIZUKU USER SERVICE CONNECTION
     * ============================================================
     */

    private val userServiceConnection =
        object :
            ServiceConnection {

            override fun onServiceConnected(
                name: ComponentName?,
                binder: IBinder?
            ) {

                if (
                    binder != null &&
                    binder.pingBinder()
                ) {

                    privilegedService =
                        IPrivilegedService
                            .Stub
                            .asInterface(
                                binder
                            )

                    Logger.i(
                        "PrivilegedService connected successfully"
                    )

                } else {

                    privilegedService =
                        null

                    Logger.e(
                        "PrivilegedService returned invalid binder"
                    )
                }
            }

            override fun onServiceDisconnected(
                name: ComponentName?
            ) {

                privilegedService =
                    null

                Logger.w(
                    "PrivilegedService disconnected"
                )
            }

            override fun onBindingDied(
                name: ComponentName?
            ) {

                privilegedService =
                    null

                Logger.w(
                    "PrivilegedService binding died"
                )
            }

            override fun onNullBinding(
                name: ComponentName?
            ) {

                privilegedService =
                    null

                Logger.e(
                    "PrivilegedService returned null binding"
                )
            }
        }

    /*
     * ============================================================
     * CONSTANTS
     * ============================================================
     */

    companion object {

        private const val
            PERMISSION_REQUEST_CODE =
            1337

        /*
         * IMPORTANT:
         *
         * This MUST now be 3.
         *
         * Previous version = 2.
         *
         * Increasing it forces Shizuku to restart
         * the UserService with the updated
         * PrivilegedServiceImpl Bluetooth fix.
         */
        private const val
            PRIVILEGED_SERVICE_VERSION =
            3
    }
}
