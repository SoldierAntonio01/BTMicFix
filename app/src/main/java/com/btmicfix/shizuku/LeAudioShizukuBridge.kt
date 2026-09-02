package com.btmicfix.shizuku

import android.Manifest
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
import androidx.core.content.ContextCompat
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
 * BluetoothManager and BluetoothAdapter run from the NORMAL
 * BTMicFix application process.
 *
 * We find the actual paired BluetoothDevice from bondedDevices,
 * rather than trusting AudioDeviceInfo.address.
 *
 * Only the privileged LE Audio Binder transaction is routed
 * through Shizuku.
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
        preferredDeviceName: String
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
         * BLUETOOTH PERMISSION
         * --------------------------------------------------------
         */

        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) !=
            PackageManager.PERMISSION_GRANTED
        ) {

            return buildResult(
                "FAILED",
                """
                BTMicFix does not have
                BLUETOOTH_CONNECT permission.
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
         */

        try {

            HiddenApiBypass
                .setHiddenApiExemptions(
                    "Landroid/bluetooth/",
                    "Lcom/android/modules/utils/"
                )

        } catch (e: Throwable) {

            Logger.w(
                "Hidden API exemption error: " +
                    "${e.javaClass.simpleName}: " +
                    "${e.message}"
            )
        }

        /*
         * --------------------------------------------------------
         * NORMAL BLUETOOTH ADAPTER
         * --------------------------------------------------------
         */

        val bluetoothManager =
            try {

                context.getSystemService(
                    BluetoothManager::class.java
                )

            } catch (_: Throwable) {

                null
            }

        if (bluetoothManager == null) {

            return buildResult(
                "FAILED",
                """
                Normal BTMicFix process could not
                obtain BluetoothManager.
                """.trimIndent()
            )
        }

        val adapter =
            try {

                bluetoothManager.adapter

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
                unexpectedly returned NULL.
                """.trimIndent()
            )
        }

        if (
            try {
                !adapter.isEnabled
            } catch (_: Throwable) {
                true
            }
        ) {

            return buildResult(
                "FAILED",
                "Bluetooth is OFF."
            )
        }

        /*
         * ========================================================
         * FIND THE REAL PAIRED BUDS DEVICE
         * ========================================================
         *
         * AudioDeviceInfo.address on your Fold6 produced:
         *
         * XX:XX:XX:XX:D4:72
         *
         * That is not usable as a BluetoothDevice MAC.
         *
         * Instead, query BluetoothAdapter.bondedDevices and find
         * Antonio's Buds4 Pro there.
         */

        val pairedDevices =
            try {

                adapter.bondedDevices
                    .toList()

            } catch (e: SecurityException) {

                return buildResult(
                    "FAILED",
                    """
                    Android blocked access to paired
                    Bluetooth devices.

                    ${e.message}
                    """.trimIndent()
                )

            } catch (e: Throwable) {

                return buildResult(
                    "FAILED",
                    """
                    Could not read paired Bluetooth devices.

                    ${e.javaClass.name}

                    ${e.message}
                    """.trimIndent()
                )
            }

        if (pairedDevices.isEmpty()) {

            return buildResult(
                "FAILED",
                """
                Android returned zero paired
                Bluetooth devices.
                """.trimIndent()
            )
        }

        val device =
            findBestMatchingDevice(
                pairedDevices =
                    pairedDevices,

                preferredName =
                    preferredDeviceName
            )

        if (device == null) {

            val pairedNames =
                pairedDevices.joinToString(
                    separator = "\n"
                ) { item ->

                    safeDeviceLabel(
                        item
                    )
                }

            return buildResult(
                "FAILED",
                """
                Could not match the selected headset
                to a paired BluetoothDevice.

                Selected audio device:
                $preferredDeviceName

                Paired Bluetooth devices:
                $pairedNames
                """.trimIndent()
            )
        }

        /*
         * This BluetoothDevice comes directly from bondedDevices,
         * so its address is the address we want for the framework
         * Bluetooth profile APIs.
         */

        val realAddress =
            try {

                device.address

            } catch (e: Throwable) {

                return buildResult(
                    "FAILED",
                    """
                    Found the paired Buds,
                    but could not read their address.

                    ${e.javaClass.name}

                    ${e.message}
                    """.trimIndent()
                )
            }

        if (
            !BluetoothAdapter
                .checkBluetoothAddress(
                    realAddress
                )
        ) {

            return buildResult(
                "FAILED",
                """
                Paired BluetoothDevice was found,
                but Android returned an invalid address.

                Device:
                ${safeDeviceLabel(device)}

                Address:
                $realAddress
                """.trimIndent()
            )
        }

        Logger.i(
            "Matched paired BluetoothDevice: " +
                safeDeviceLabel(device)
        )

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

                                Received:
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
                         * PRIVILEGED SHIZUKU CALL
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
                                    ${safeDeviceLabel(device)}

                                    Real paired-device address:
                                    $realAddress

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

                                    Wait about 5 seconds.

                                    BTMicFix will retry the
                                    BLE Headset route automatically.
                                    """.trimIndent()
                                )
                            )

                        } else {

                            result.set(
                                buildResult(
                                    "REJECTED",
                                    """
                                    The privileged LE Audio Binder
                                    was reached successfully.

                                    Android returned FALSE.

                                    Device:
                                    ${safeDeviceLabel(device)}

                                    Real paired-device address:
                                    $realAddress

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
                                Android rejected the privileged
                                LE Audio Binder transaction.

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
         * REQUEST LE AUDIO PROFILE
         * --------------------------------------------------------
         */

        val started =
            try {

                adapter.getProfileProxy(
                    context.applicationContext,
                    listener,
                    BluetoothProfile.LE_AUDIO
                )

            } catch (e: SecurityException) {

                return buildResult(
                    "PERMISSION DENIED",
                    """
                    Normal BTMicFix process could not
                    request the LE Audio profile.

                    ${e.message}
                    """.trimIndent()
                )

            } catch (e: Throwable) {

                return buildResult(
                    "FAILED",
                    """
                    LE Audio profile request failed.

                    ${e.javaClass.name}

                    ${e.message}
                    """.trimIndent()
                )
            }

        if (!started) {

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
                        "LE Audio callback returned no result."
                    )

            } else {

                buildResult(
                    "TIMEOUT",
                    """
                    Bluetooth works and the paired Buds
                    were found.

                    Android did not deliver the
                    LE Audio profile callback within
                    $PROFILE_WAIT_SECONDS seconds.
                    """.trimIndent()
                )
            }

        /*
         * Release local Java proxy only.
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
            "LE Audio bridge result:\n" +
                finalResult
        )

        return finalResult
    }

    /*
     * ============================================================
     * FIND MATCHING PAIRED BLUETOOTH DEVICE
     * ============================================================
     */

    private fun findBestMatchingDevice(
        pairedDevices: List<BluetoothDevice>,
        preferredName: String
    ): BluetoothDevice? {

        val wanted =
            preferredName
                .trim()

        /*
         * #1 exact alias match
         */

        pairedDevices.firstOrNull { device ->

            val alias =
                try {
                    device.alias
                } catch (_: Throwable) {
                    null
                }

            alias?.equals(
                wanted,
                ignoreCase = true
            ) == true

        }?.let {

            return it
        }

        /*
         * #2 exact Bluetooth name
         */

        pairedDevices.firstOrNull { device ->

            val name =
                try {
                    device.name
                } catch (_: Throwable) {
                    null
                }

            name?.equals(
                wanted,
                ignoreCase = true
            ) == true

        }?.let {

            return it
        }

        /*
         * #3 Buds4 Pro match.
         *
         * Handles aliases such as:
         *
         * Antonio's Buds4 Pro
         * Galaxy Buds4 Pro
         */

        val budsMatches =
            pairedDevices.filter { device ->

                val alias =
                    try {
                        device.alias.orEmpty()
                    } catch (_: Throwable) {
                        ""
                    }

                val name =
                    try {
                        device.name.orEmpty()
                    } catch (_: Throwable) {
                        ""
                    }

                alias.contains(
                    "Buds4 Pro",
                    ignoreCase = true
                ) ||
                    name.contains(
                        "Buds4 Pro",
                        ignoreCase = true
                    )
            }

        if (
            budsMatches.size == 1
        ) {

            return budsMatches.first()
        }

        /*
         * #4 more forgiving:
         * Galaxy Buds + Pro
         */

        val looseMatches =
            pairedDevices.filter { device ->

                val text =
                    safeDeviceLabel(
                        device
                    )

                text.contains(
                    "Buds",
                    ignoreCase = true
                ) &&
                    text.contains(
                        "Pro",
                        ignoreCase = true
                    )
            }

        if (
            looseMatches.size == 1
        ) {

            return looseMatches.first()
        }

        return null
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
         * Get the hidden IBluetoothLeAudio service.
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
         * Wrap Bluetooth Binder with Shizuku.
         */

        val privilegedBinder =
            ShizukuBinderWrapper(
                rawBinder
            )

        /*
         * Recreate hidden IBluetoothLeAudio interface.
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
         * Binder transaction is performed by shell.
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
            findSetPolicyMethod(
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
     * FIND setConnectionPolicy
     * ============================================================
     */

    private fun findSetPolicyMethod(
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
         * Modern Android:
         *
         * BluetoothDevice
         * int
         * AttributionSource
         */

        val modern =
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

        if (modern != null) {

            modern.isAccessible =
                true

            return SetPolicyCall(
                method =
                    modern,

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
         * Older form.
         */

        val legacy =
            methods.firstOrNull { method ->

                val types =
                    method.parameterTypes

                types.size == 2 &&

                    types[0] ==
                    BluetoothDevice::class.java &&

                    types[1] ==
                    Int::class.javaPrimitiveType
            }

        if (legacy != null) {

            legacy.isAccessible =
                true

            return SetPolicyCall(
                method =
                    legacy,

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
     * READ CONNECTION POLICY
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

    private fun safeDeviceLabel(
        device: BluetoothDevice
    ): String {

        val alias =
            try {
                device.alias
            } catch (_: Throwable) {
                null
            }

        val name =
            try {
                device.name
            } catch (_: Throwable) {
                null
            }

        return when {

            !alias.isNullOrBlank() ->
                alias

            !name.isNullOrBlank() ->
                name

            else ->
                "Unnamed Bluetooth Device"
        }
    }

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
