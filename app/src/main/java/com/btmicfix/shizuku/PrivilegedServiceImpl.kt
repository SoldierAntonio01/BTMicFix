package com.btmicfix.shizuku

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import androidx.annotation.Keep
import com.btmicfix.IPrivilegedService
import com.btmicfix.util.Logger
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Privileged Shizuku UserService.
 *
 * Runs as Android shell UID.
 *
 * Experimental purpose:
 * Enable the hidden LE Audio profile for a paired Bluetooth headset.
 */
class PrivilegedServiceImpl :
    IPrivilegedService.Stub {

    private var serviceContext: Context? = null

    /*
     * Shizuku fallback constructor.
     */
    constructor() : super() {

        Logger.i(
            "PrivilegedService created without Context"
        )
    }

    /*
     * Shizuku v13+ constructor.
     */
    @Keep
    constructor(
        context: Context
    ) : super() {

        serviceContext =
            context

        Logger.i(
            "PrivilegedService received Context: " +
                context.javaClass.name
        )
    }

    companion object {

        /*
         * BluetoothProfile.CONNECTION_POLICY_ALLOWED
         */
        private const val
            CONNECTION_POLICY_ALLOWED =
            100

        private const val
            LE_AUDIO_WAIT_SECONDS =
            12L

        private const val
            CONNECTION_SETTLE_MS =
            4000L

        private val ALLOWED_PREFIXES =
            listOf(
                "dumpsys audio",
                "dumpsys bluetooth_manager",
                "cmd audio",
                "cmd bluetooth_manager",
                "settings get"
            )
    }

    /*
     * ============================================================
     * DESTROY
     * ============================================================
     */

    override fun destroy() {

        Logger.i(
            "PrivilegedService destroyed"
        )

        System.exit(0)
    }

    /*
     * ============================================================
     * ORIGINAL SHELL COMMAND SUPPORT
     * ============================================================
     */

    override fun executeAudioCommand(
        command: String
    ): String? {

        if (
            ALLOWED_PREFIXES.none {
                command.startsWith(it)
            }
        ) {

            return "ERROR: Command not allowed"
        }

        return try {

            val process =
                Runtime
                    .getRuntime()
                    .exec(
                        arrayOf(
                            "sh",
                            "-c",
                            command
                        )
                    )

            val output =
                process
                    .inputStream
                    .bufferedReader()
                    .readText()

            val error =
                process
                    .errorStream
                    .bufferedReader()
                    .readText()

            val exitCode =
                process.waitFor()

            if (exitCode == 0) {

                output.trim()

            } else {

                "ERROR: $error"
            }

        } catch (e: Exception) {

            "EXCEPTION: ${e.message}"
        }
    }

    override fun getAudioDump():
        String? {

        return executeAudioCommand(
            "dumpsys audio"
        )
    }

    override fun forceAudioStrategy(
        strategy: Int,
        deviceType: Int
    ): Boolean {

        if (
            strategy !in 0..15 ||
            deviceType !in 0..30
        ) {

            return false
        }

        return try {

            val result =
                executeAudioCommand(
                    "cmd audio set-force-use " +
                        "$strategy $deviceType"
                )

            result != null &&
                !result.startsWith(
                    "ERROR"
                )

        } catch (_: Exception) {

            false
        }
    }

    /*
     * ============================================================
     * BLUETOOTH ADAPTER RESOLUTION
     * ============================================================
     *
     * getDefaultAdapter() returned NULL on your Fold6 while
     * running inside Shizuku.
     *
     * This tries multiple methods.
     */

    private fun resolveBluetoothAdapter(
        context: Context
    ): Pair<BluetoothAdapter?, String> {

        val report =
            StringBuilder()

        /*
         * METHOD 1
         *
         * Official modern Android method.
         */
        try {

            val manager =
                context.getSystemService(
                    BluetoothManager::class.java
                )

            if (manager == null) {

                report.appendLine(
                    "Method 1: BluetoothManager = NULL"
                )

            } else {

                report.appendLine(
                    "Method 1: BluetoothManager = OK"
                )

                val adapter =
                    manager.adapter

                if (adapter != null) {

                    report.appendLine(
                        "Method 1: BluetoothAdapter = OK"
                    )

                    return Pair(
                        adapter,
                        report.toString()
                    )

                } else {

                    report.appendLine(
                        "Method 1: BluetoothAdapter = NULL"
                    )
                }
            }

        } catch (e: Throwable) {

            report.appendLine(
                "Method 1 error: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
        }

        /*
         * METHOD 2
         *
         * Ask Context by Bluetooth service name.
         */
        try {

            val service =
                context.getSystemService(
                    Context.BLUETOOTH_SERVICE
                )

            if (service is BluetoothManager) {

                report.appendLine(
                    "Method 2: Bluetooth service = BluetoothManager"
                )

                val adapter =
                    service.adapter

                if (adapter != null) {

                    report.appendLine(
                        "Method 2: BluetoothAdapter = OK"
                    )

                    return Pair(
                        adapter,
                        report.toString()
                    )

                } else {

                    report.appendLine(
                        "Method 2: BluetoothAdapter = NULL"
                    )
                }

            } else {

                report.appendLine(
                    "Method 2: Bluetooth service = " +
                        (
                            service
                                ?.javaClass
                                ?.name
                                ?: "NULL"
                            )
                )
            }

        } catch (e: Throwable) {

            report.appendLine(
                "Method 2 error: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
        }

        /*
         * METHOD 3
         *
         * BluetoothManager's constructor is hidden from regular apps,
         * but Shizuku UserService does not have normal hidden-API
         * restrictions.
         *
         * Construct BluetoothManager directly using the Shizuku
         * Context.
         */
        try {

            val constructor =
                BluetoothManager::class.java
                    .getDeclaredConstructor(
                        Context::class.java
                    )

            constructor.isAccessible =
                true

            val manager =
                constructor.newInstance(
                    context
                )

            report.appendLine(
                "Method 3: reflected BluetoothManager = OK"
            )

            val adapter =
                manager.adapter

            if (adapter != null) {

                report.appendLine(
                    "Method 3: BluetoothAdapter = OK"
                )

                return Pair(
                    adapter,
                    report.toString()
                )

            } else {

                report.appendLine(
                    "Method 3: BluetoothAdapter = NULL"
                )
            }

        } catch (e: Throwable) {

            report.appendLine(
                "Method 3 error: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
        }

        /*
         * METHOD 4
         *
         * Old static fallback.
         *
         * This is what failed previously, but keep it as the
         * final fallback.
         */
        try {

            @Suppress("DEPRECATION")
            val adapter =
                BluetoothAdapter
                    .getDefaultAdapter()

            if (adapter != null) {

                report.appendLine(
                    "Method 4: getDefaultAdapter = OK"
                )

                return Pair(
                    adapter,
                    report.toString()
                )

            } else {

                report.appendLine(
                    "Method 4: getDefaultAdapter = NULL"
                )
            }

        } catch (e: Throwable) {

            report.appendLine(
                "Method 4 error: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
        }

        return Pair(
            null,
            report.toString()
        )
    }

    /*
     * ============================================================
     * FORCE LE AUDIO
     * ============================================================
     */

    override fun forceLeAudio(
        address: String
    ): String {

        Logger.i(
            "Force LE Audio requested for $address"
        )

        if (
            !BluetoothAdapter
                .checkBluetoothAddress(
                    address
                )
        ) {

            return buildResult(
                "FAILED",
                "Invalid Bluetooth address:\n$address"
            )
        }

        val context =
            serviceContext

        if (context == null) {

            return buildResult(
                "FAILED",
                """
                Shizuku UserService has no Context.

                The Context constructor was not used.
                """.trimIndent()
            )
        }

        /*
         * ========================================================
         * NEW ADAPTER RESOLUTION
         * ========================================================
         */

        val adapterResult =
            resolveBluetoothAdapter(
                context
            )

        val adapter =
            adapterResult.first

        val adapterReport =
            adapterResult.second

        if (adapter == null) {

            return buildResult(
                "FAILED",
                """
                Bluetooth adapter could not be obtained.

                Adapter diagnostic:

                $adapterReport
                """.trimIndent()
            )
        }

        Logger.i(
            "Bluetooth adapter resolved:\n" +
                adapterReport
        )

        /*
         * ========================================================
         * BLUETOOTH STATE
         * ========================================================
         */

        val enabled =
            try {

                adapter.isEnabled

            } catch (e: Throwable) {

                return buildResult(
                    "FAILED",
                    """
                    Bluetooth adapter exists,
                    but Android blocked isEnabled().

                    ${e.javaClass.name}
                    ${e.message}

                    Adapter diagnostic:
                    $adapterReport
                    """.trimIndent()
                )
            }

        if (!enabled) {

            return buildResult(
                "FAILED",
                """
                Bluetooth adapter found,
                but Bluetooth reports OFF.

                Adapter diagnostic:
                $adapterReport
                """.trimIndent()
            )
        }

        /*
         * ========================================================
         * GET BUDS DEVICE
         * ========================================================
         */

        val device =
            try {

                adapter.getRemoteDevice(
                    address
                )

            } catch (e: Throwable) {

                return buildResult(
                    "FAILED",
                    """
                    Bluetooth adapter works,
                    but getRemoteDevice failed.

                    ${e.javaClass.name}
                    ${e.message}

                    Adapter diagnostic:
                    $adapterReport
                    """.trimIndent()
                )
            }

        val result =
            AtomicReference<String?>(
                null
            )

        val proxyReference =
            AtomicReference<BluetoothProfile?>(
                null
            )

        val latch =
            CountDownLatch(
                1
            )

        /*
         * ========================================================
         * LE AUDIO PROFILE CALLBACK
         * ========================================================
         */

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
                                Android returned the wrong profile.

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

                    proxyReference.set(
                        proxy
                    )

                    try {

                        val deviceName =
                            try {

                                device.name
                                    ?: "Unknown Bluetooth Device"

                            } catch (_: Throwable) {

                                "Bluetooth Device"
                            }

                        /*
                         * Existing LE Audio state.
                         */

                        val oldState =
                            try {

                                proxy.getConnectionState(
                                    device
                                )

                            } catch (_: Throwable) {

                                -1
                            }

                        val oldPolicy =
                            getConnectionPolicyReflectively(
                                proxy,
                                device
                            )

                        Logger.i(
                            "LE Audio proxy connected. " +
                                "Old policy=$oldPolicy, " +
                                "state=${connectionStateName(oldState)}"
                        )

                        /*
                         * =================================================
                         * ENABLE LE AUDIO
                         * =================================================
                         */

                        val policyAccepted =
                            setLeAudioConnectionPolicy(
                                proxy,
                                device,
                                CONNECTION_POLICY_ALLOWED
                            )

                        Logger.i(
                            "setConnectionPolicy(ALLOWED) = " +
                                policyAccepted
                        )

                        if (!policyAccepted) {

                            result.set(
                                buildResult(
                                    "REJECTED",
                                    """
                                    We successfully reached
                                    Android's LE Audio service.

                                    Device:
                                    $deviceName

                                    Address:
                                    $address

                                    Previous LE policy:
                                    $oldPolicy

                                    Previous LE state:
                                    ${connectionStateName(oldState)}

                                    Android returned FALSE from:

                                    setConnectionPolicy(
                                        device,
                                        ALLOWED
                                    )

                                    Adapter diagnostic:
                                    $adapterReport
                                    """.trimIndent()
                                )
                            )

                            latch.countDown()

                            return
                        }

                        /*
                         * Give Samsung's Bluetooth stack time to start
                         * the LE Audio connection.
                         */

                        try {

                            Thread.sleep(
                                CONNECTION_SETTLE_MS
                            )

                        } catch (_: InterruptedException) {
                        }

                        val newState =
                            try {

                                proxy.getConnectionState(
                                    device
                                )

                            } catch (_: Throwable) {

                                -1
                            }

                        val newPolicy =
                            getConnectionPolicyReflectively(
                                proxy,
                                device
                            )

                        val connectedDevices =
                            try {

                                val devices =
                                    proxy.connectedDevices

                                if (devices.isEmpty()) {

                                    "NONE"

                                } else {

                                    devices.joinToString(
                                        separator = "\n"
                                    ) { connected ->

                                        val name =
                                            try {

                                                connected.name
                                                    ?: "LE Device"

                                            } catch (_: Throwable) {

                                                "LE Device"
                                            }

                                        val addr =
                                            try {

                                                connected.address

                                            } catch (_: Throwable) {

                                                "UNKNOWN"
                                            }

                                        "$name [$addr]"
                                    }
                                }

                            } catch (e: Throwable) {

                                "ERROR: ${e.message}"
                            }

                        if (
                            newState ==
                            BluetoothProfile.STATE_CONNECTED
                        ) {

                            result.set(
                                buildResult(
                                    "SUCCESS",
                                    """
                                    LE AUDIO CONNECTED!

                                    Device:
                                    $deviceName

                                    Address:
                                    $address

                                    Old policy:
                                    $oldPolicy

                                    New policy:
                                    $newPolicy

                                    Old state:
                                    ${connectionStateName(oldState)}

                                    New state:
                                    ${connectionStateName(newState)}

                                    LE Audio connected devices:
                                    $connectedDevices

                                    Adapter:
                                    $adapterReport

                                    Now press Retry in BTMicFix.
                                    """.trimIndent()
                                )
                            )

                        } else {

                            /*
                             * Policy was accepted, but Samsung may still
                             * be negotiating the actual connection.
                             */

                            result.set(
                                buildResult(
                                    "ACCEPTED",
                                    """
                                    Android ACCEPTED the LE Audio policy.

                                    Device:
                                    $deviceName

                                    Address:
                                    $address

                                    Old policy:
                                    $oldPolicy

                                    New policy:
                                    $newPolicy

                                    Old state:
                                    ${connectionStateName(oldState)}

                                    Current state:
                                    ${connectionStateName(newState)}

                                    LE Audio devices:
                                    $connectedDevices

                                    Adapter:
                                    $adapterReport

                                    Wait 5 seconds and press Retry.
                                    """.trimIndent()
                                )
                            )
                        }

                    } catch (
                        e: InvocationTargetException
                    ) {

                        val realError =
                            e.targetException
                                ?: e

                        result.set(
                            buildResult(
                                "FAILED",
                                """
                                LE Audio API threw:

                                ${realError.javaClass.name}

                                ${realError.message}

                                Adapter:
                                $adapterReport
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
                                We reached Bluetooth LE Audio,
                                but Android denied the privileged operation.

                                ${e.javaClass.name}

                                ${e.message}

                                Adapter:
                                $adapterReport
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

                                Adapter:
                                $adapterReport
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
                                LE Audio profile service disconnected.

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
         * ========================================================
         * REQUEST LE AUDIO PROFILE PROXY
         * ========================================================
         */

        val requestStarted =
            try {

                adapter.getProfileProxy(
                    context,
                    listener,
                    BluetoothProfile.LE_AUDIO
                )

            } catch (e: SecurityException) {

                return buildResult(
                    "PERMISSION DENIED",
                    """
                    Bluetooth adapter works.

                    But Android blocked:

                    getProfileProxy(LE_AUDIO)

                    ${e.javaClass.name}

                    ${e.message}

                    Adapter:
                    $adapterReport
                    """.trimIndent()
                )

            } catch (e: Throwable) {

                return buildResult(
                    "FAILED",
                    """
                    Bluetooth adapter works.

                    But LE Audio profile request failed.

                    ${e.javaClass.name}

                    ${e.message}

                    Adapter:
                    $adapterReport
                    """.trimIndent()
                )
            }

        if (!requestStarted) {

            return buildResult(
                "FAILED",
                """
                Bluetooth adapter works.

                But:

                getProfileProxy(
                    LE_AUDIO
                )

                returned FALSE.

                Adapter:
                $adapterReport
                """.trimIndent()
            )
        }

        /*
         * ========================================================
         * WAIT FOR PROFILE CALLBACK
         * ========================================================
         */

        val callbackReceived =
            try {

                latch.await(
                    LE_AUDIO_WAIT_SECONDS,
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
                    Bluetooth adapter works and Android accepted
                    the LE Audio profile-proxy request.

                    However, the LE Audio callback never arrived
                    within $LE_AUDIO_WAIT_SECONDS seconds.

                    Adapter:
                    $adapterReport
                    """.trimIndent()
                )
            }

        /*
         * Release our proxy.
         *
         * This does NOT disable LE Audio.
         */

        proxyReference
            .get()
            ?.let { proxy ->

                try {

                    adapter.closeProfileProxy(
                        BluetoothProfile.LE_AUDIO,
                        proxy
                    )

                } catch (_: Throwable) {
                }
            }

        Logger.i(
            finalResult
        )

        return finalResult
    }

    /*
     * ============================================================
     * HIDDEN LE AUDIO METHODS
     * ============================================================
     */

    private fun setLeAudioConnectionPolicy(
        proxy: BluetoothProfile,
        device: BluetoothDevice,
        policy: Int
    ): Boolean {

        val method =
            proxy
                .javaClass
                .methods
                .firstOrNull { candidate ->

                    candidate.name ==
                        "setConnectionPolicy" &&

                        candidate
                            .parameterTypes
                            .size == 2 &&

                        candidate
                            .parameterTypes[0] ==
                            BluetoothDevice::class.java
                }
                ?: throw NoSuchMethodException(
                    "BluetoothLeAudio.setConnectionPolicy(" +
                        "BluetoothDevice, int)"
                )

        method.isAccessible =
            true

        val response =
            method.invoke(
                proxy,
                device,
                policy
            )

        return response as? Boolean
            ?: false
    }

    private fun getConnectionPolicyReflectively(
        proxy: BluetoothProfile,
        device: BluetoothDevice
    ): String {

        return try {

            val method =
                proxy
                    .javaClass
                    .methods
                    .firstOrNull { candidate ->

                        candidate.name ==
                            "getConnectionPolicy" &&

                            candidate
                                .parameterTypes
                                .size == 1 &&

                            candidate
                                .parameterTypes[0] ==
                            BluetoothDevice::class.java
                    }
                    ?: return "METHOD NOT FOUND"

            method.isAccessible =
                true

            val response =
                method.invoke(
                    proxy,
                    device
                )

            when (
                response as? Int
            ) {

                100 ->
                    "ALLOWED (100)"

                0 ->
                    "UNKNOWN (0)"

                -1 ->
                    "FORBIDDEN (-1)"

                null ->
                    "UNKNOWN RESULT"

                else ->
                    response.toString()
            }

        } catch (
            e: InvocationTargetException
        ) {

            val cause =
                e.targetException
                    ?: e

            "ERROR: " +
                "${cause.javaClass.simpleName}: " +
                "${cause.message}"

        } catch (
            e: Throwable
        ) {

            "ERROR: " +
                "${e.javaClass.simpleName}: " +
                "${e.message}"
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
            ===== BTMicFix FORCE LE AUDIO =====
            $title

            $details
            ===================================
        """.trimIndent()
    }
    }
