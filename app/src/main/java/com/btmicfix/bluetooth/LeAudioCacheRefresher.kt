package com.btmicfix.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothLeAudio
import android.bluetooth.BluetoothLeAudioCodecConfig
import android.bluetooth.BluetoothLeAudioCodecStatus
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.AttributionSource
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.IInterface
import androidx.core.content.ContextCompat
import com.btmicfix.util.Logger
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.lang.reflect.InvocationTargetException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Reads the ACTUAL negotiated LE Audio codec configuration.
 *
 * Shows:
 *
 * Phone -> Buds output codec + sample rate
 * Buds mic -> Phone input codec + sample rate
 *
 * BluetoothLeAudio.getCodecStatus() requires BLUETOOTH_PRIVILEGED,
 * so the final Binder transaction is passed through Shizuku.
 */
object LeAudioCodecMonitor {

    private const val SHELL_UID =
        2000

    private const val SHELL_PACKAGE =
        "com.android.shell"

    private const val PROFILE_TIMEOUT_SECONDS =
        10L

    private const val RECEIVER_TIMEOUT_SECONDS =
        5L

    data class CodecSnapshot(
        val success: Boolean,
        val outputText: String,
        val inputText: String,
        val details: String
    )

    fun read(
        context: Context,
        preferredDeviceName: String
    ): CodecSnapshot {

        /*
         * ========================================================
         * PERMISSION
         * ========================================================
         */

        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            return failure(
                "BTMicFix does not have BLUETOOTH_CONNECT permission."
            )
        }

        /*
         * ========================================================
         * SHIZUKU
         * ========================================================
         */

        try {

            if (!Shizuku.pingBinder()) {

                return failure(
                    "Shizuku is not running."
                )
            }

            if (
                Shizuku.checkSelfPermission() !=
                PackageManager.PERMISSION_GRANTED
            ) {

                return failure(
                    "BTMicFix does not have Shizuku permission."
                )
            }

        } catch (e: Throwable) {

            return failure(
                "Shizuku error: ${e.message}"
            )
        }

        /*
         * ========================================================
         * HIDDEN API ACCESS
         * ========================================================
         */

        try {

            HiddenApiBypass.setHiddenApiExemptions(
                "Landroid/bluetooth/",
                "Lcom/android/modules/utils/"
            )

        } catch (e: Throwable) {

            Logger.w(
                "Hidden API exemption error: ${e.message}"
            )
        }

        /*
         * ========================================================
         * BLUETOOTH ADAPTER
         * ========================================================
         */

        val manager =
            try {

                context.getSystemService(
                    BluetoothManager::class.java
                )

            } catch (_: Throwable) {

                null
            }
                ?: return failure(
                    "BluetoothManager unavailable."
                )

        val adapter =
            try {

                manager.adapter

            } catch (e: Throwable) {

                return failure(
                    "BluetoothAdapter error: ${e.message}"
                )
            }
                ?: return failure(
                    "BluetoothAdapter returned NULL."
                )

        /*
         * ========================================================
         * PROFILE CALLBACK
         * ========================================================
         */

        val result =
            AtomicReference<CodecSnapshot?>(
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
                            failure(
                                "Android returned the wrong Bluetooth profile."
                            )
                        )

                        latch.countDown()

                        return
                    }

                    proxyReference.set(
                        proxy
                    )

                    try {

                        val leAudio =
                            proxy as? BluetoothLeAudio
                                ?: throw IllegalStateException(
                                    "Profile is not BluetoothLeAudio"
                                )

                        /*
                         * ----------------------------------------
                         * FIND CONNECTED BUDS
                         * ----------------------------------------
                         */

                        val connected =
                            leAudio.connectedDevices

                        if (
                            connected.isEmpty()
                        ) {

                            result.set(
                                failure(
                                    "No LE Audio device is currently connected."
                                )
                            )

                            latch.countDown()

                            return
                        }

                        val device =
                            findBestDevice(
                                connected,
                                preferredDeviceName
                            )
                                ?: connected.first()

                        /*
                         * ----------------------------------------
                         * GET LE AUDIO GROUP
                         * ----------------------------------------
                         */

                        val groupId =
                            leAudio.getGroupId(
                                device
                            )

                        if (
                            groupId ==
                            BluetoothLeAudio.GROUP_ID_INVALID
                        ) {

                            result.set(
                                failure(
                                    "The connected Buds do not currently have a valid LE Audio group ID."
                                )
                            )

                            latch.countDown()

                            return
                        }

                        /*
                         * ----------------------------------------
                         * GET ACTUAL CODEC STATUS
                         * ----------------------------------------
                         */

                        val codecStatus =
                            getCodecStatusThroughShizuku(
                                proxy =
                                    proxy,

                                groupId =
                                    groupId
                            )

                        if (
                            codecStatus ==
                            null
                        ) {

                            result.set(
                                failure(
                                    "Android returned no active LE Audio codec status."
                                )
                            )

                            latch.countDown()

                            return
                        }

                        val outputConfig =
                            codecStatus.outputCodecConfig

                        val inputConfig =
                            codecStatus.inputCodecConfig

                        val outputText =
                            describeConfig(
                                outputConfig
                            )

                        val inputText =
                            describeConfig(
                                inputConfig
                            )

                        val name =
                            safeName(
                                device
                            )

                        result.set(
                            CodecSnapshot(
                                success =
                                    true,

                                outputText =
                                    outputText,

                                inputText =
                                    inputText,

                                details =
                                    """
                                    ===== BTMicFix ACTUAL LE CODEC =====

                                    Device:
                                    $name

                                    LE Audio group:
                                    $groupId


                                    PHONE -> BUDS

                                    $outputText


                                    BUDS MIC -> PHONE

                                    $inputText


                                    These values come from Android's
                                    current Bluetooth LE Audio codec
                                    configuration, not from the
                                    Developer Options preference.

                                    ==================================
                                    """.trimIndent()
                            )
                        )

                    } catch (e: Throwable) {

                        val actual =
                            unwrap(
                                e
                            )

                        result.set(
                            failure(
                                """
                                ${actual.javaClass.name}

                                ${actual.message}
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
                        result.get() ==
                        null
                    ) {

                        result.set(
                            failure(
                                "LE Audio service disconnected."
                            )
                        )
                    }

                    latch.countDown()
                }
            }

        /*
         * ========================================================
         * REQUEST LE AUDIO PROFILE
         * ========================================================
         */

        val started =
            try {

                adapter.getProfileProxy(
                    context.applicationContext,
                    listener,
                    BluetoothProfile.LE_AUDIO
                )

            } catch (e: Throwable) {

                return failure(
                    """
                    Could not open BluetoothLeAudio profile.

                    ${e.message}
                    """.trimIndent()
                )
            }

        if (!started) {

            return failure(
                "BluetoothLeAudio profile request returned FALSE."
            )
        }

        /*
         * ========================================================
         * WAIT
         * ========================================================
         */

        val completed =
            try {

                latch.await(
                    PROFILE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )

            } catch (_: InterruptedException) {

                false
            }

        val finalResult =
            if (completed) {

                result.get()
                    ?: failure(
                        "Codec query returned no result."
                    )

            } else {

                failure(
                    "Timed out waiting for the LE Audio codec service."
                )
            }

        /*
         * ========================================================
         * RELEASE LOCAL PROFILE PROXY
         * ========================================================
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

        return finalResult
    }

    /*
     * ============================================================
     * GET CODEC STATUS THROUGH SHIZUKU
     * ============================================================
     */

    private fun getCodecStatusThroughShizuku(
        proxy: BluetoothProfile,
        groupId: Int
    ): BluetoothLeAudioCodecStatus? {

        /*
         * Get the hidden IBluetoothLeAudio interface.
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
                    "LE Audio service does not implement IInterface"
                )

        val rawBinder: IBinder =
            rawInterface.asBinder()

        if (!rawBinder.isBinderAlive) {

            throw IllegalStateException(
                "LE Audio Binder is not alive"
            )
        }

        val privilegedBinder =
            ShizukuBinderWrapper(
                rawBinder
            )

        val stubClass =
            Class.forName(
                "android.bluetooth.IBluetoothLeAudio\$Stub"
            )

        val service =
            HiddenApiBypass.invoke(
                stubClass,
                null,
                "asInterface",
                privilegedBinder
            )
                ?: throw IllegalStateException(
                    "IBluetoothLeAudio.Stub.asInterface returned null"
                )

        val source =
            AttributionSource
                .Builder(
                    SHELL_UID
                )
                .setPackageName(
                    SHELL_PACKAGE
                )
                .build()

        val methods =
            service
                .javaClass
                .methods
                .filter {

                    it.name ==
                        "getCodecStatus"
                }

        /*
         * --------------------------------------------------------
         * DIRECT FORM
         * --------------------------------------------------------
         *
         * getCodecStatus(
         *     int,
         *     AttributionSource
         * )
         */

        val direct =
            methods.firstOrNull { method ->

                val types =
                    method.parameterTypes

                types.size ==
                    2 &&

                    types[0] ==
                    Int::class.javaPrimitiveType &&

                    types[1].name ==
                    "android.content.AttributionSource"
            }

        if (
            direct !=
            null
        ) {

            direct.isAccessible =
                true

            return direct.invoke(
                service,
                groupId,
                source
            ) as? BluetoothLeAudioCodecStatus
        }

        /*
         * --------------------------------------------------------
         * SYNCHRONOUS RESULT RECEIVER FORM
         * --------------------------------------------------------
         *
         * getCodecStatus(
         *     int,
         *     AttributionSource,
         *     SynchronousResultReceiver
         * )
         */

        val receiverMethod =
            methods.firstOrNull { method ->

                val types =
                    method.parameterTypes

                types.size ==
                    3 &&

                    types[0] ==
                    Int::class.javaPrimitiveType &&

                    types[1].name ==
                    "android.content.AttributionSource" &&

                    types[2].name.contains(
                        "SynchronousResultReceiver"
                    )
            }

        if (
            receiverMethod !=
            null
        ) {

            receiverMethod.isAccessible =
                true

            val receiver =
                createReceiver()

            receiverMethod.invoke(
                service,
                groupId,
                source,
                receiver
            )

            return awaitReceiverValue(
                receiver
            ) as? BluetoothLeAudioCodecStatus
        }

        val signatures =
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
                    ")"
            }

        throw NoSuchMethodException(
            """
            No supported getCodecStatus method.

            Samsung exposed:

            $signatures
            """.trimIndent()
        )
    }

    /*
     * ============================================================
     * RESULT RECEIVER
     * ============================================================
     */

    private fun createReceiver():
        Any {

        val receiverClass =
            Class.forName(
                "com.android.modules.utils.SynchronousResultReceiver"
            )

        val get =
            receiverClass
                .methods
                .firstOrNull {

                    it.name ==
                        "get" &&

                    it.parameterCount ==
                        0
                }
                ?: throw NoSuchMethodException(
                    "SynchronousResultReceiver.get()"
                )

        get.isAccessible =
            true

        return get.invoke(
            null
        )
            ?: throw IllegalStateException(
                "SynchronousResultReceiver.get() returned null"
            )
    }

    private fun awaitReceiverValue(
        receiver: Any
    ): Any? {

        val await =
            receiver
                .javaClass
                .methods
                .firstOrNull {

                    it.name ==
                        "awaitResultNoInterrupt" &&

                    it.parameterCount ==
                        1
                }
                ?: return null

        await.isAccessible =
            true

        val argument: Any =
            when (
                await.parameterTypes[0]
            ) {

                Duration::class.java ->

                    Duration.ofSeconds(
                        RECEIVER_TIMEOUT_SECONDS
                    )

                Long::class.javaPrimitiveType,
                java.lang.Long::class.java ->

                    RECEIVER_TIMEOUT_SECONDS *
                        1000L

                else ->

                    return null
            }

        val result =
            await.invoke(
                receiver,
                argument
            )
                ?: return null

        val getValue =
            result
                .javaClass
                .methods
                .firstOrNull {

                    it.name ==
                        "getValue" &&

                    it.parameterCount ==
                        1
                }
                ?: return null

        getValue.isAccessible =
            true

        return getValue.invoke(
            result,
            *arrayOfNulls<Any>(
                1
            )
        )
    }

    /*
     * ============================================================
     * CONFIG DESCRIPTION
     * ============================================================
     */

    private fun describeConfig(
        config: BluetoothLeAudioCodecConfig?
    ): String {

        if (
            config ==
            null
        ) {

            return "Not active"
        }

        val codecName =
            try {

                config.codecName

            } catch (_: Throwable) {

                when (
                    config.codecType
                ) {

                    0 ->
                        "LC3"

                    1 ->
                        "Opus"

                    2 ->
                        "Opus Hi-Res"

                    else ->
                        "Codec ${config.codecType}"
                }
            }

        val sampleRate =
            formatSampleRate(
                config.sampleRate
            )

        return "$codecName • $sampleRate"
    }

    /*
     * ============================================================
     * SAMPLE RATE BIT MASK
     * ============================================================
     *
     * Avoid referencing API-35 constants directly because this
     * project currently compiles against SDK 34.
     */

    private fun formatSampleRate(
        mask: Int
    ): String {

        if (
            mask ==
            0
        ) {

            return "Sample rate unavailable"
        }

        val rates =
            listOf(
                1 to "8 kHz",
                2 to "11.025 kHz",
                4 to "16 kHz",
                8 to "22.05 kHz",
                16 to "24 kHz",
                32 to "32 kHz",
                64 to "44.1 kHz",
                128 to "48 kHz",
                256 to "88.2 kHz",
                512 to "96 kHz",
                1024 to "176.4 kHz",
                2048 to "192 kHz",
                4096 to "384 kHz"
            )

        val active =
            rates
                .filter {
                    pair ->

                    mask and pair.first !=
                        0
                }
                .map {
                    it.second
                }

        return if (
            active.isEmpty()
        ) {

            "Unknown rate mask $mask"

        } else {

            active.joinToString(
                " / "
            )
        }
    }

    /*
     * ============================================================
     * DEVICE MATCH
     * ============================================================
     */

    private fun findBestDevice(
        devices: List<BluetoothDevice>,
        preferredName: String
    ): BluetoothDevice? {

        val wanted =
            preferredName.trim()

        devices
            .firstOrNull {

                safeName(it)
                    .equals(
                        wanted,
                        ignoreCase =
                            true
                    )
            }
            ?.let {

                return it
            }

        val buds =
            devices.filter {

                safeName(it)
                    .contains(
                        "Buds",
                        ignoreCase =
                            true
                    )
            }

        return if (
            buds.size ==
            1
        ) {

            buds.first()

        } else {

            null
        }
    }

    private fun safeName(
        device: BluetoothDevice
    ): String {

        return try {

            device.alias
                ?: device.name
                ?: "Bluetooth Device"

        } catch (_: Throwable) {

            "Bluetooth Device"
        }
    }

    private fun unwrap(
        error: Throwable
    ): Throwable {

        return if (
            error is InvocationTargetException
        ) {

            error.targetException
                ?: error

        } else {

            error
        }
    }

    private fun failure(
        message: String
    ): CodecSnapshot {

        return CodecSnapshot(
            success =
                false,

            outputText =
                "Unavailable",

            inputText =
                "Unavailable",

            details =
                """
                ===== BTMicFix ACTUAL LE CODEC =====
                FAILED

                $message

                ==================================
                """.trimIndent()
        )
    }
}
