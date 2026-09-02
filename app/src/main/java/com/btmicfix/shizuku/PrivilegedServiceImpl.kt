package com.btmicfix.shizuku

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
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
 * Shizuku UserService.
 *
 * Runs as Android shell UID through Shizuku.
 *
 * In addition to the original audio commands, this version can attempt
 * to enable the hidden Bluetooth LE Audio profile for a paired headset.
 */
class PrivilegedServiceImpl :
    IPrivilegedService.Stub {

    private var serviceContext:
        Context? = null

    /**
     * Required fallback constructor.
     */
    constructor() : super() {
        Logger.i(
            "PrivilegedService created without Context"
        )
    }

    /**
     * Shizuku v13+ should use this constructor.
     *
     * The Context is needed by BluetoothAdapter.getProfileProxy().
     */
    @Keep
    constructor(
        context: Context
    ) : super() {

        serviceContext =
            context

        Logger.i(
            "PrivilegedService created with Shizuku Context"
        )
    }

    companion object {

        /*
         * BluetoothProfile.CONNECTION_POLICY_ALLOWED
         *
         * Hidden/system API constant value.
         */
        private const val
            CONNECTION_POLICY_ALLOWED =
            100

        private const val
            LE_AUDIO_WAIT_SECONDS =
            10L

        private const val
            CONNECTION_SETTLE_MS =
            3000L

        /*
         * Keep shell command execution restricted.
         */
        private val ALLOWED_PREFIXES =
            listOf(
                "dumpsys audio",
                "dumpsys bluetooth_manager",
                "cmd audio",
                "cmd bluetooth_manager",
                "settings get"
            )
    }

    override fun destroy() {

        Logger.i(
            "PrivilegedService: destroy()"
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

            Logger.e(
                "Blocked command: $command"
            )

            return "ERROR: Command not in allowlist"
        }

        return try {

            Logger.d(
                "PrivilegedService command: $command"
            )

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

                Logger.e(
                    "Command failed " +
                        "(exit=$exitCode): $error"
                )

                "ERROR: $error"
            }

        } catch (e: Exception) {

            Logger.e(
                "Command exception",
                e
            )

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

            Logger.e(
                "Invalid strategy=$strategy " +
                    "deviceType=$deviceType"
            )

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

        } catch (e: Exception) {

            Logger.e(
                "forceAudioStrategy failed",
                e
            )

            false
        }
    }

    /*
     * ============================================================
     * FORCE LE AUDIO
     * ============================================================
     *
     * THIS IS THE NEW PART.
     *
     * We ask Android for the Bluetooth LE Audio profile proxy.
     *
     * BluetoothLeAudio.setConnectionPolicy() is not a normal
     * third-party API, so it is invoked through reflection while
     * running as Shizuku's shell UID.
     *
     * CONNECTION_POLICY_ALLOWED causes Android's Bluetooth stack
     * to enable/connect the LE Audio profile if Samsung allows it
     * for this paired device.
     */

    override fun forceLeAudio(
        address: String
    ): String {

        Logger.i(
            "Force LE Audio requested for $address"
        )

        /*
         * Validate the MAC address before doing anything.
         */
        if (
            !BluetoothAdapter
                .checkBluetoothAddress(
                    address
                )
        ) {

            return buildResult(
                title =
                    "FAILED",
                details =
                    "Invalid Bluetooth address: $address"
            )
        }

        val context =
            serviceContext

        if (context == null) {

            return buildResult(
                title =
                    "FAILED",
                details =
                    "Shizuku UserService did not receive a Context."
            )
        }

        val adapter =
            BluetoothAdapter
                .getDefaultAdapter()

        if (adapter == null) {

            return buildResult(
                title =
                    "FAILED",
                details =
                    "BluetoothAdapter is unavailable."
            )
        }

        if (!adapter.isEnabled) {

            return buildResult(
                title =
                    "FAILED",
                details =
                    "Bluetooth is currently OFF."
            )
        }

        val device =
            try {

                adapter.getRemoteDevice(
                    address
                )

            } catch (e: Exception) {

                return buildResult(
                    title =
                        "FAILED",
                    details =
                        "Could not create BluetoothDevice.\n" +
                            "${e.javaClass.simpleName}: ${e.message}"
                )
            }

        /*
         * Result from the asynchronous Bluetooth profile callback.
         */
        val result =
            AtomicReference<String>(
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
                                title =
                                    "FAILED",
                                details =
                                    "Android returned unexpected " +
                                        "Bluetooth profile $profile."
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

                            } catch (_: Exception) {

                                "Bluetooth Device"
                            }

                        val oldState =
                            try {

                                proxy.getConnectionState(
                                    device
                                )

                            } catch (_: Exception) {

                                -1
                            }

                        val oldPolicy =
                            getConnectionPolicyReflectively(
                                proxy,
                                device
                            )

                        /*
                         * Hidden method:
                         *
                         * BluetoothLeAudio.setConnectionPolicy(
                         *     BluetoothDevice,
                         *     CONNECTION_POLICY_ALLOWED
                         * )
                         */
                        val accepted =
                            setLeAudioConnectionPolicy(
                                proxy,
                                device,
                                CONNECTION_POLICY_ALLOWED
                            )

                        Logger.i(
                            "LE Audio setConnectionPolicy returned $accepted"
                        )

                        if (!accepted) {

                            result.set(
                                buildResult(
                                    title =
                                        "REJECTED",
                                    details =
                                        """
                                        Device: $deviceName
                                        Address: $address
                                        LE profile proxy: YES
                                        Previous policy: $oldPolicy
                                        Previous state: ${connectionStateName(oldState)}

                                        Samsung/Android returned FALSE from
                                        setConnectionPolicy(ALLOWED).

                                        LE Audio was NOT enabled.
                                        """.trimIndent()
                                )
                            )

                            latch.countDown()

                            return
                        }

                        /*
                         * Give the Bluetooth stack a few seconds to
                         * establish the LE Audio connection.
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

                            } catch (_: Exception) {

                                -1
                            }

                        val newPolicy =
                            getConnectionPolicyReflectively(
                                proxy,
                                device
                            )

                        val connectedDevices =
                            try {

                                proxy
                                    .connectedDevices
                                    .joinToString(
                                        separator = "\n"
                                    ) { connected ->

                                        val name =
                                            try {
                                                connected.name
                                                    ?: "LE Device"
                                            } catch (_: Exception) {
                                                "LE Device"
                                            }

                                        val addr =
                                            try {
                                                connected.address
                                            } catch (_: Exception) {
                                                "UNKNOWN"
                                            }

                                        "$name [$addr]"
                                    }

                            } catch (e: Exception) {

                                "Unable to read: ${e.message}"
                            }

                        if (
                            newState ==
                            BluetoothProfile.STATE_CONNECTED
                        ) {

                            result.set(
                                buildResult(
                                    title =
                                        "SUCCESS",
                                    details =
                                        """
                                        LE AUDIO CONNECTED.

                                        Device: $deviceName
                                        Address: $address

                                        Old policy: $oldPolicy
                                        New policy: $newPolicy

                                        Old state: ${connectionStateName(oldState)}
                                        New state: ${connectionStateName(newState)}

                                        LE connected devices:
                                        $connectedDevices

                                        Now return to BTMicFix and press Retry.
                                        """.trimIndent()
                                )
                            )

                        } else {

                            result.set(
                                buildResult(
                                    title =
                                        "ACCEPTED",
                                    details =
                                        """
                                        Android accepted
                                        setConnectionPolicy(ALLOWED).

                                        Device: $deviceName
                                        Address: $address

                                        Old policy: $oldPolicy
                                        New policy: $newPolicy

                                        Old state: ${connectionStateName(oldState)}
                                        Current state: ${connectionStateName(newState)}

                                        LE connected devices:
                                        $connectedDevices

                                        The connection may still be starting.

                                        Wait about 5 seconds, then press Retry
                                        in BTMicFix.
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
                                title =
                                    "FAILED",
                                details =
                                    """
                                    Hidden LE Audio API threw an exception.

                                    ${realError.javaClass.name}

                                    ${realError.message}
                                    """.trimIndent()
                            )
                        )

                    } catch (
                        e: SecurityException
                    ) {

                        result.set(
                            buildResult(
                                title =
                                    "PERMISSION DENIED",
                                details =
                                    """
                                    Android denied the LE Audio privileged call.

                                    ${e.javaClass.name}

                                    ${e.message}

                                    Shizuku is running, but Samsung may have
                                    added another permission or UID check.
                                    """.trimIndent()
                            )
                        )

                    } catch (
                        e: Throwable
                    ) {

                        result.set(
                            buildResult(
                                title =
                                    "FAILED",
                                details =
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
                                title =
                                    "FAILED",
                                details =
                                    "LE Audio profile service disconnected."
                            )
                        )
                    }

                    latch.countDown()
                }
            }

        val requestStarted =
            try {

                adapter.getProfileProxy(
                    context,
                    listener,
                    BluetoothProfile.LE_AUDIO
                )

            } catch (
                e: SecurityException
            ) {

                return buildResult(
                    title =
                        "PERMISSION DENIED",
                    details =
                        """
                        Android blocked getProfileProxy(LE_AUDIO).

                        ${e.message}
                        """.trimIndent()
                )

            } catch (
                e: Throwable
            ) {

                return buildResult(
                    title =
                        "FAILED",
                    details =
                        """
                        Could not request LE Audio profile.

                        ${e.javaClass.name}
                        ${e.message}
                        """.trimIndent()
                )
            }

        if (!requestStarted) {

            return buildResult(
                title =
                    "FAILED",
                details =
                    """
                    BluetoothAdapter.getProfileProxy()
                    returned FALSE for LE_AUDIO.

                    The Fold6 reports LE Audio hardware support,
                    but Samsung did not provide an LE Audio profile proxy.
                    """.trimIndent()
            )
        }

        /*
         * Wait for BluetoothProfile.ServiceListener.
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
                        title =
                            "FAILED",
                        details =
                            "LE Audio callback returned no result."
                    )

            } else {

                buildResult(
                    title =
                        "TIMEOUT",
                    details =
                        """
                        Android never delivered the LE Audio
                        profile callback within $LE_AUDIO_WAIT_SECONDS seconds.

                        This usually means Samsung is not exposing the
                        LE Audio profile to the shell process.
                        """.trimIndent()
                )
            }

        /*
         * Closing our proxy does NOT disable the Bluetooth profile.
         * It only releases this app's proxy object.
         */
        proxyReference
            .get()
            ?.let { proxy ->

                try {

                    adapter.closeProfileProxy(
                        BluetoothProfile.LE_AUDIO,
                        proxy
                    )

                } catch (_: Exception) {
                }
            }

        Logger.i(
            "Force LE Audio result:\n$finalResult"
        )

        return finalResult
    }

    /*
     * ============================================================
     * HIDDEN API REFLECTION
     * ============================================================
     */

    private fun setLeAudioConnectionPolicy(
        proxy: BluetoothProfile,
        device: BluetoothDevice,
        policy: Int
    ): Boolean {

        /*
         * Runtime class should be android.bluetooth.BluetoothLeAudio.
         *
         * We deliberately use reflection because setConnectionPolicy()
         * is a privileged/system method on the Android versions we are
         * targeting.
         */

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

            "ERROR: ${cause.javaClass.simpleName}: ${cause.message}"

        } catch (
            e: Throwable
        ) {

            "ERROR: ${e.javaClass.simpleName}: ${e.message}"
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
