package com.btmicfix.shizuku

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.AttributionSource
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import com.btmicfix.util.Logger
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Experimental LE Audio bridge.
 *
 * IMPORTANT:
 *
 * This runs BluetoothAdapter / BluetoothManager from the NORMAL
 * BTMicFix application process.
 *
 * That solves the problem we found on the Fold6 where:
 *
 * BluetoothManager = OK
 * BluetoothAdapter = NULL
 *
 * inside a Shizuku UserService.
 *
 * Only the final privileged Binder transaction is passed through
 * ShizukuBinderWrapper.
 *
 *
 * Flow:
 *
 * BTMicFix normal process
 *      ↓
 * BluetoothAdapter works
 *      ↓
 * Bluetooth LE Audio profile proxy
 *      ↓
 * Get underlying IBluetoothLeAudio Binder
 *      ↓
 * ShizukuBinderWrapper
 *      ↓
 * Android receives call as shell UID
 *      ↓
 * setConnectionPolicy(ALLOWED)
 *      ↓
 * Bluetooth stack attempts LE Audio connection
 */
object LeAudioShizukuBridge {

    private const val CONNECTION_POLICY_ALLOWED =
        100

    private const val SHELL_UID =
        2000

    private const val SHELL_PACKAGE =
        "com.android.shell"

    private const val PROFILE_WAIT_SECONDS =
        12L

    /*
     * ============================================================
     * PUBLIC ENTRY POINT
     * ============================================================
     */

    fun forceLeAudio(
        context: Context,
        address: String
    ): String {

        /*
         * --------------------------------------------------------
         * ANDROID VERSION
         * --------------------------------------------------------
         */

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.TIRAMISU
        ) {

            return buildResult(
                "FAILED",
                """
                Android 13 or newer is required
                for the LE Audio profile API.
                """.trimIndent()
            )
        }

        /*
         * --------------------------------------------------------
         * SHIZUKU
         * --------------------------------------------------------
         */

        try {

            if (!Shizuku.pingBinder()) {

                return buildResult(
                    "FAILED",
                    """
                    Shizuku is not running.

                    Open Shizuku and start it,
                    then try again.
                    """.trimIndent()
                )
            }

            if (
                Shizuku.checkSelfPermission() !=
                PackageManager.PERMISSION_GRANTED
            ) {

                return buildResult(
                    "FAILED",
                    """
                    BTMicFix does not have
                    Shizuku permission.
                    """.trimIndent()
                )
            }

        } catch (e: Throwable) {

            return buildResult(
                "FAILED",
                """
                Could not communicate with Shizuku.

                ${e.javaClass.name}

                ${e.message}
                """.trimIndent()
            )
        }

        /*
         * --------------------------------------------------------
         * HIDDEN API ACCESS
         * --------------------------------------------------------
         *
         * This does NOT grant privileges.
         *
         * It only allows us to obtain Android's hidden
         * IBluetoothLeAudio Binder interface.
         *
         * The privileged transaction itself still goes
         * through Shizuku.
         */

        try {

            val exemptionWorked =
                HiddenApiBypass
                    .setHiddenApiExemptions(
                        "Landroid/bluetooth/",
                        "Lcom/android/modules/utils/"
                    )

            Logger.i(
                "Hidden API exemption result = " +
                    exemptionWorked
            )

        } catch (e: Throwable) {

            Logger.w(
                "Hidden API exemption setup threw: " +
                    "${e.javaClass.simpleName}: " +
                    "${e.message}"
            )
        }

        /*
         * --------------------------------------------------------
         * NORMAL APP BLUETOOTH ADAPTER
         * --------------------------------------------------------
         */

        val manager =
            try {

                context.getSystemService(
                    BluetoothManager::class.java
                )

            } catch (e: Throwable) {

                null
            }

        if (manager == null) {

            return buildResult(
                "FAILED",
                """
                Normal app BluetoothManager
                could not be obtained.
                """.trimIndent()
            )
        }

        val adapter =
            try {

                manager.adapter

            } catch (e: Throwable) {

                return buildResult(
                    "FAILED",
                    """
                    BluetoothManager exists,
                    but adapter access failed.

                    ${e.javaClass.name}

                    ${e.message}
                    """.trimIndent()
                )
            }

        if (adapter == null) {

            return buildResult(
                "FAILED",
                """
                Normal app BluetoothAdapter
                is NULL.

                This would be unexpected because
                the main BTMicFix app already sees
                Antonio's Buds4 Pro.
                """.trimIndent()
            )
        }

        val bluetoothEnabled =
            try {

                adapter.isEnabled

            } catch (e: Throwable) {

                false
            }

        if (!bluetoothEnabled) {

            return buildResult(
                "FAILED",
                "Bluetooth is OFF."
            )
        }

        /*
         * --------------------------------------------------------
         * VALIDATE ADDRESS
         * --------------------------------------------------------
         */

        if (
            !BluetoothAdapter
                .checkBluetoothAddress(
                    address
                )
        ) {

            return buildResult(
                "FAILED",
                """
                Invalid Bluetooth address:

                $address
                """.trimIndent()
            )
        }

        val device =
            try {

                adapter.getRemoteDevice(
                    address
                )

            } catch (e: Throwable) {

                return buildResult(
                    "FAILED",
                    """
                    Could not obtain the
                    BluetoothDevice.

                    ${e.javaClass.name}

                    ${e.message}
                    """.trimIndent()
                )
            }

        /*
         * --------------------------------------------------------
         * LE AUDIO PROFILE PROXY
         * --------------------------------------------------------
         */

        val result =
            AtomicReference<String?>(
                null
            )

        val profileReference =
            AtomicReference<BluetoothProfile?>(
                null
            )

        val latch =
            CountDownLatch(
                1
            )

        val listener =
            object :
                BluetoothProfile.ServiceListener {

                override fun onServiceConnected(
                    profile: Int,
                    proxy: BluetoothProfile
                ) {

                    if (
                        profile !=
                        BluetoothProfile.LE_AUDIO
                    ) {

                        result.set(
                            buildResult(
                                "FAILED",
                                """
                                Android returned the wrong
                                Bluetooth profile.

                                Expected:
                                LE_AUDIO

                                Received profile ID:
                                $profile
                                """.trimIndent()
                            )
                        )

                        latch.countDown()

                        return
                    }

                    profileReference.set(
                        proxy
                    )

                    try {

                        val normalStateBefore =
                            try {

                                proxy.getConnectionState(
                                    device
                                )

                            } catch (_: Throwable) {

                                -1
                            }

                        /*
                         * =================================================
                         * THIS IS THE IMPORTANT CALL
                         * =================================================
                         */

                        val privilegedResult =
                            callSetConnectionPolicyThroughShizuku(
                                proxy =
                                    proxy,
                                device =
                                    device
                            )

                        val normalStateImmediatelyAfter =
                            try {

                                proxy.getConnectionState(
                                    device
                                )

                            } catch (_: Throwable) {

                                -1
                            }

                        val deviceName =
                            try {

                                device.name
                                    ?: "Bluetooth Device"

                            } catch (_: Throwable) {

                                "Bluetooth Device"
                            }

                        if (
                            privilegedResult.accepted
                        ) {

                            result.set(
                                buildResult(
                                    "ACCEPTED",
                                    """
                                    Android accepted the privileged
                                    LE Audio connection-policy request.

                                    Device:
                                    $deviceName

                                    Address:
                                    $address

                                    Binder call:
                                    ${privilegedResult.signature}

                                    Policy before:
                                    ${privilegedResult.policyBefore}

                                    Policy after:
                                    ${privilegedResult.policyAfter}

                                    LE state before:
                                    ${connectionStateName(normalStateBefore)}

                                    LE state immediately after:
                                    ${connectionStateName(normalStateImmediatelyAfter)}

                                    Wait a few seconds.

                                    BTMicFix will then retry the
                                    BLE Headset route automatically.
                                    """.trimIndent()
                                )
                            )

                        } else {

                            result.set(
                                buildResult(
                                    "REJECTED",
                                    """
                                    We successfully reached the
                                    privileged LE Audio Binder,
                                    but Android returned FALSE.

                                    Device:
                                    $deviceName

                                    Address:
                                    $address

                                    Binder call:
                                    ${privilegedResult.signature}

                                    Policy before:
                                    ${privilegedResult.policyBefore}

                                    Policy after:
                                    ${privilegedResult.policyAfter}

                                    LE state:
                                    ${connectionStateName(normalStateBefore)}
                                    """.trimIndent()
                                )
                            )
                        }

                    } catch (
                        e: InvocationTargetException
                    ) {

                        val actual =
                            e.targetException
                                ?: e

                        result.set(
                            buildResult(
                                "FAILED",
                                """
                                Android's LE Audio Binder threw:

                                ${actual.javaClass.name}

                                ${actual.message}

                                This means we reached farther
                                than the old UserService method.
                                """.trimIndent()
                            )
                        )

                    } catch (
                        e: SecurityException
                    ) {

                        result.set(
                            buildResult(
                                "PERMISSION DENIED",
                                """
                                Android rejected the
                                privileged Binder transaction.

                                ${e.javaClass.name}

                                ${e.message}
                                """.trimIndent()
                            )
                        )

                    } catch (
                        e: Throwable
                    ) {

                        result.set(
                            buildResult(
                                "FAILED",
                                """
                                ${e.javaClass.name}

                                ${e.message}
                                """.trimIndent()
                            )
                        )

                    } finally {

                        latch.countDown()
                    }
                }

                override fun onServiceDisconnected(
                    profile: Int
                ) {

                    if (
                        result.get() == null
                    ) {

                        result.set(
                            buildResult(
                                "FAILED",
                                """
                                LE Audio profile service
                                disconnected unexpectedly.

                                Profile:
                                $profile
                                """.trimIndent()
                            )
                        )
                    }

                    latch.countDown()
                }
            }

        /*
         * --------------------------------------------------------
         * START PROFILE CONNECTION
         * --------------------------------------------------------
         */

        val profileRequestStarted =
            try {

                adapter.getProfileProxy(
                    context.applicationContext,
                    listener,
                    BluetoothProfile.LE_AUDIO
                )

            } catch (
                e: SecurityException
            ) {

                return buildResult(
                    "PERMISSION DENIED",
                    """
                    Normal BTMicFix process could not
                    request the LE Audio profile proxy.

                    ${e.message}
                    """.trimIndent()
                )

            } catch (
                e: Throwable
            ) {

                return buildResult(
                    "FAILED",
                    """
                    LE Audio profile-proxy request
                    threw an exception.

                    ${e.javaClass.name}

                    ${e.message}
                    """.trimIndent()
                )
            }

        if (!profileRequestStarted) {

            return buildResult(
                "FAILED",
                """
                BluetoothAdapter.getProfileProxy(
                    LE_AUDIO
                )

                returned FALSE.
                """.trimIndent()
            )
        }

        /*
         * --------------------------------------------------------
         * WAIT FOR PROFILE CALLBACK
         * --------------------------------------------------------
         *
         * HomeScreen calls this function on Dispatchers.IO,
         * not on Android's main UI thread.
         */

        val callbackReceived =
            try {

                latch.await(
                    PROFILE_WAIT_SECONDS,
                    TimeUnit.SECONDS
                )

            } catch (_: InterruptedException) {

                false
            }

        val finalResult =
            if (callbackReceived) {

                result.get()
                    ?: buildResult(
                        "FAILED",
                        """
                        LE Audio callback returned
                        without a result.
                        """.trimIndent()
                    )

            } else {

                buildResult(
                    "TIMEOUT",
                    """
                    The normal Bluetooth adapter works,
                    but Android did not deliver the
                    LE Audio service callback within
                    $PROFILE_WAIT_SECONDS seconds.
                    """.trimIndent()
                )
            }

        /*
         * Closing our local profile proxy does NOT
         * disable LE Audio.
         *
         * It only releases BTMicFix's Java proxy.
         */

        profileReference
            .get()
            ?.let { profile ->

                try {

                    adapter.closeProfileProxy(
                        BluetoothProfile.LE_AUDIO,
                        profile
                    )

                } catch (_: Throwable) {
                }
            }

        Logger.i(
            "LE Audio Shizuku Bridge:\n" +
                finalResult
        )

        return finalResult
    }

    /*
     * ============================================================
     * SHIZUKU BINDER WRAPPER
     * ============================================================
     */

    private fun callSetConnectionPolicyThroughShizuku(
        proxy: BluetoothProfile,
        device: BluetoothDevice
    ): PrivilegedPolicyResult {

        /*
         * BluetoothLeAudio contains a private:
         *
         * getService()
         *
         * returning Android's hidden
         * IBluetoothLeAudio interface.
         */

        val rawService =
            HiddenApiBypass.invoke(
                proxy.javaClass,
                proxy,
                "getService"
            )
                ?: throw IllegalStateException(
                    "BluetoothLeAudio.getService() returned null"
                )

        val rawInterface =
            rawService as? IInterface
                ?: throw IllegalStateException(
                    "LE Audio service does not implement IInterface. " +
                        "Actual=${rawService.javaClass.name}"
                )

        val rawBinder: IBinder =
            rawInterface.asBinder()

        if (!rawBinder.isBinderAlive) {

            throw IllegalStateException(
                "Raw LE Audio Binder is not alive"
            )
        }

        /*
         * THIS is where Shizuku becomes important.
         *
         * The underlying LE Audio service Binder remains the same,
         * but transact() is forwarded through Shizuku.
         */

        val privilegedBinder =
            ShizukuBinderWrapper(
                rawBinder
            )

        /*
         * Recreate:
         *
         * android.bluetooth.IBluetoothLeAudio
         *
         * around our Shizuku-wrapped Binder.
         */

        val stubClass =
            Class.forName(
                "android.bluetooth.IBluetoothLeAudio\$Stub"
            )

        val privilegedService =
            HiddenApiBypass.invoke(
                stubClass,
                null,
                "asInterface",
                privilegedBinder
            )
                ?: throw IllegalStateException(
                    "IBluetoothLeAudio.Stub.asInterface returned null"
                )

        /*
         * The Binder caller is now shell UID 2000.
         *
         * The AttributionSource therefore also needs to describe
         * the shell caller rather than the normal BTMicFix app UID.
         */

        val shellSource =
            AttributionSource
                .Builder(
                    SHELL_UID
                )
                .setPackageName(
                    SHELL_PACKAGE
                )
                .build()

        val policyBefore =
            getPolicyIfPossible(
                service =
                    privilegedService,
                device =
                    device,
                source =
                    shellSource
            )

        val setCall =
            findAndroid16SetPolicyMethod(
                privilegedService
            )

        val accepted =
            setCall.method.invoke(
                privilegedService,
                *setCall.arguments(
                    device,
                    shellSource
                )
            ) as? Boolean
                ?: false

        val policyAfter =
            getPolicyIfPossible(
                service =
                    privilegedService,
                device =
                    device,
                source =
                    shellSource
            )

        return PrivilegedPolicyResult(
            accepted =
                accepted,

            signature =
                setCall.signature,

            policyBefore =
                policyBefore,

            policyAfter =
                policyAfter
        )
    }

    /*
     * ============================================================
     * FIND CORRECT ANDROID VERSION OF setConnectionPolicy
     * ============================================================
     *
     * Android has changed the hidden Binder method signature
     * between releases.
     *
     * Your Fold6 is Android 16, where the important form is:
     *
     * setConnectionPolicy(
     *     BluetoothDevice,
     *     int,
     *     AttributionSource
     * )
     *
     * We also support older two-argument Binder implementations.
     */

    private fun findAndroid16SetPolicyMethod(
        service: Any
    ): SetPolicyCall {

        val methods =
            HiddenApiBypass
                .getDeclaredMethods(
                    service.javaClass
                )
                .filterIsInstance<Method>()
                .filter {
                    it.name ==
                        "setConnectionPolicy"
                }

        /*
         * Android 15 / 16 style:
         *
         * device, policy, AttributionSource
         */

        val threeArgument =
            methods.firstOrNull { method ->

                val types =
                    method.parameterTypes

                types.size == 3 &&

                    types[0] ==
                    BluetoothDevice::class.java &&

                    types[1] ==
                    Int::class.javaPrimitiveType &&

                    types[2].name ==
                    "android.content.AttributionSource"
            }

        if (threeArgument != null) {

            threeArgument.isAccessible =
                true

            return SetPolicyCall(
                method =
                    threeArgument,

                signature =
                    "setConnectionPolicy(" +
                        "BluetoothDevice, int, AttributionSource" +
                        ")",

                argumentBuilder = {
                        device,
                        source ->

                    arrayOf(
                        device,
                        CONNECTION_POLICY_ALLOWED,
                        source
                    )
                }
            )
        }

        /*
         * Older Android style:
         *
         * device, policy
         */

        val twoArgument =
            methods.firstOrNull { method ->

                val types =
                    method.parameterTypes

                types.size == 2 &&

                    types[0] ==
                    BluetoothDevice::class.java &&

                    types[1] ==
                    Int::class.javaPrimitiveType
            }

        if (twoArgument != null) {

            twoArgument.isAccessible =
                true

            return SetPolicyCall(
                method =
                    twoArgument,

                signature =
                    "setConnectionPolicy(" +
                        "BluetoothDevice, int" +
                        ")",

                argumentBuilder = {
                        device,
                        _ ->

                    arrayOf(
                        device,
                        CONNECTION_POLICY_ALLOWED
                    )
                }
            )
        }

        /*
         * If Samsung has a different Binder signature,
         * print every one we found instead of blindly guessing.
         */

        val found =
            if (methods.isEmpty()) {

                "NONE"

            } else {

                methods.joinToString(
                    separator = "\n"
                ) { method ->

                    method.name +
                        "(" +
                        method.parameterTypes
                            .joinToString(
                                ", "
                            ) {
                                it.simpleName
                            } +
                        ") -> " +
                        method.returnType.simpleName
                }
            }

        throw NoSuchMethodException(
            """
            Could not find a supported
            IBluetoothLeAudio.setConnectionPolicy method.

            Methods found:

            $found
            """.trimIndent()
        )
    }

    /*
     * ============================================================
     * READ POLICY THROUGH SAME PRIVILEGED BINDER
     * ============================================================
     */

    private fun getPolicyIfPossible(
        service: Any,
        device: BluetoothDevice,
        source: AttributionSource
    ): String {

        return try {

            val methods =
                HiddenApiBypass
                    .getDeclaredMethods(
                        service.javaClass
                    )
                    .filterIsInstance<Method>()
                    .filter {
                        it.name ==
                            "getConnectionPolicy"
                    }

            /*
             * Modern:
             *
             * BluetoothDevice,
             * AttributionSource
             */

            val modern =
                methods.firstOrNull { method ->

                    val types =
                        method.parameterTypes

                    types.size == 2 &&

                        types[0] ==
                        BluetoothDevice::class.java &&

                        types[1].name ==
                        "android.content.AttributionSource"
                }

            val value =
                if (modern != null) {

                    modern.isAccessible =
                        true

                    modern.invoke(
                        service,
                        device,
                        source
                    ) as? Int

                } else {

                    /*
                     * Older:
                     *
                     * BluetoothDevice
                     */

                    val legacy =
                        methods.firstOrNull { method ->

                            val types =
                                method.parameterTypes

                            types.size == 1 &&

                                types[0] ==
                                BluetoothDevice::class.java
                        }

                    if (legacy != null) {

                        legacy.isAccessible =
                            true

                        legacy.invoke(
                            service,
                            device
                        ) as? Int

                    } else {

                        null
                    }
                }

            when (value) {

                100 ->
                    "ALLOWED (100)"

                0 ->
                    "UNKNOWN (0)"

                -1 ->
                    "FORBIDDEN (-1)"

                null ->
                    "UNAVAILABLE"

                else ->
                    value.toString()
            }

        } catch (e: Throwable) {

            val actual =
                if (
                    e is InvocationTargetException
                ) {

                    e.targetException
                        ?: e

                } else {

                    e
                }

            "ERROR: " +
                "${actual.javaClass.simpleName}: " +
                "${actual.message}"
        }
    }

    /*
     * ============================================================
     * HELPERS
     * ============================================================
     */

    private fun connectionStateName(
        state: Int
    ): String {

        return when (state) {

            BluetoothProfile.STATE_CONNECTED ->
                "CONNECTED"

            BluetoothProfile.STATE_CONNECTING ->
                "CONNECTING"

            BluetoothProfile.STATE_DISCONNECTING ->
                "DISCONNECTING"

            BluetoothProfile.STATE_DISCONNECTED ->
                "DISCONNECTED"

            else ->
                "UNKNOWN ($state)"
        }
    }

    private fun buildResult(
        title: String,
        details: String
    ): String {

        return """
            ===== BTMicFix LE BINDER TEST =====
            $title

            $details
            ===================================
        """.trimIndent()
    }

    private data class PrivilegedPolicyResult(
        val accepted: Boolean,
        val signature: String,
        val policyBefore: String,
        val policyAfter: String
    )

    private data class SetPolicyCall(
        val method: Method,
        val signature: String,
        val argumentBuilder:
            (
                BluetoothDevice,
                AttributionSource
            ) -> Array<Any>
    ) {

        fun arguments(
            device: BluetoothDevice,
            source: AttributionSource
        ): Array<Any> {

            return argumentBuilder(
                device,
                source
            )
        }
    }
}
