package com.btmicfix.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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

/**
 * Refreshes Android's Bluetooth UUID cache over TRANSPORT_LE.
 *
 * For your Buds:
 *
 * Live GATT already proved:
 *
 * ASCS 0x184E = YES
 * PACS 0x1850 = YES
 * BASS 0x184F = YES
 *
 * Android's LE Audio connection gate needs the cached
 * LE_AUDIO / ASCS UUID 0x184E.
 *
 * PACS is useful diagnostic information but is not required
 * here before attempting the LE Audio profile connection.
 */
object LeAudioCacheRefresher {

    private const val SHELL_UID =
        2000

    private const val SHELL_PACKAGE =
        "com.android.shell"

    private const val ASCS_UUID =
        "0000184e-0000-1000-8000-00805f9b34fb"

    private const val PACS_UUID =
        "00001850-0000-1000-8000-00805f9b34fb"

    private const val BASS_UUID =
        "0000184f-0000-1000-8000-00805f9b34fb"

    private const val CACHE_WAIT_MS =
        20000L

    private const val CACHE_POLL_MS =
        500L

    private const val RECEIVER_WAIT_SECONDS =
        5L

    /*
     * ============================================================
     * PUBLIC RESULT
     * ============================================================
     *
     * HomeScreen.kt and VoiceAccessMonitorService.kt depend on
     * these exact fields:
     *
     * cacheUpdated
     * hasAscs
     * hasPacs
     * hasBass
     * text
     */

    data class RefreshResult(

        val requestAccepted:
            Boolean,

        val cacheUpdated:
            Boolean,

        val hasAscs:
            Boolean,

        val hasPacs:
            Boolean,

        val hasBass:
            Boolean,

        val text:
            String
    )

    /*
     * ============================================================
     * PUBLIC ENTRY
     * ============================================================
     */

    fun refresh(
        context: Context,
        preferredDeviceName: String
    ): RefreshResult {

        /*
         * ========================================================
         * BLUETOOTH PERMISSION
         * ========================================================
         */

        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) !=
            PackageManager.PERMISSION_GRANTED
        ) {

            return failure(
                """
                BTMicFix does not have
                BLUETOOTH_CONNECT permission.
                """.trimIndent()
            )
        }

        /*
         * ========================================================
         * SHIZUKU
         * ========================================================
         */

        try {

            if (
                !Shizuku.pingBinder()
            ) {

                return failure(
                    """
                    Shizuku is not running.

                    Open Shizuku and make sure
                    it says Running.
                    """.trimIndent()
                )
            }

            if (
                Shizuku.checkSelfPermission() !=
                PackageManager.PERMISSION_GRANTED
            ) {

                return failure(
                    """
                    BTMicFix does not have
                    Shizuku permission.
                    """.trimIndent()
                )
            }

        } catch (
            e: Throwable
        ) {

            return failure(
                """
                Could not communicate with Shizuku.

                ${e.javaClass.name}

                ${e.message}
                """.trimIndent()
            )
        }

        /*
         * ========================================================
         * HIDDEN API ACCESS
         * ========================================================
         */

        try {

            HiddenApiBypass
                .setHiddenApiExemptions(
                    "Landroid/bluetooth/",
                    "Lcom/android/modules/utils/"
                )

        } catch (
            e: Throwable
        ) {

            Logger.w(
                "Hidden API exemption error: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
        }

        /*
         * ========================================================
         * BLUETOOTH MANAGER
         * ========================================================
         */

        val manager =
            try {

                context.getSystemService(
                    BluetoothManager::class.java
                )

            } catch (
                _: Throwable
            ) {

                null
            }

        if (
            manager ==
            null
        ) {

            return failure(
                "BluetoothManager is unavailable."
            )
        }

        /*
         * ========================================================
         * BLUETOOTH ADAPTER
         * ========================================================
         */

        val adapter =
            try {

                manager.adapter

            } catch (
                e: Throwable
            ) {

                return failure(
                    """
                    BluetoothAdapter access failed.

                    ${e.javaClass.name}

                    ${e.message}
                    """.trimIndent()
                )
            }

        if (
            adapter ==
            null
        ) {

            return failure(
                "BluetoothAdapter returned NULL."
            )
        }

        val enabled =
            try {

                adapter.isEnabled

            } catch (
                _: Throwable
            ) {

                false
            }

        if (
            !enabled
        ) {

            return failure(
                "Bluetooth is OFF."
            )
        }

        /*
         * ========================================================
         * PAIRED DEVICES
         * ========================================================
         */

        val bondedDevices =
            try {

                adapter
                    .bondedDevices
                    .toList()

            } catch (
                e: Throwable
            ) {

                return failure(
                    """
                    Could not read paired
                    Bluetooth devices.

                    ${e.javaClass.name}

                    ${e.message}
                    """.trimIndent()
                )
            }

        /*
         * ========================================================
         * FIND THE BUDS
         * ========================================================
         */

        val device =
            findBestMatchingDevice(
                devices =
                    bondedDevices,

                preferredName =
                    preferredDeviceName
            )

        if (
            device ==
            null
        ) {

            return failure(
                """
                Could not identify:

                $preferredDeviceName

                in Android's paired
                Bluetooth devices.
                """.trimIndent()
            )
        }

        val deviceName =
            safeDeviceLabel(
                device
            )

        val maskedAddress =
            maskAddress(
                try {

                    device.address

                } catch (
                    _: Throwable
                ) {

                    ""
                }
            )

        /*
         * ========================================================
         * CACHE BEFORE REFRESH
         * ========================================================
         */

        val before =
            readCachedUuids(
                device
            )

        val beforeAscs =
            hasUuid(
                before,
                ASCS_UUID
            )

        val beforePacs =
            hasUuid(
                before,
                PACS_UUID
            )

        val beforeBass =
            hasUuid(
                before,
                BASS_UUID
            )

        /*
         * ========================================================
         * FAST PATH
         * ========================================================
         *
         * 184E already exists.
         *
         * No privileged refresh needs to happen.
         */

        if (
            beforeAscs
        ) {

            return RefreshResult(

                requestAccepted =
                    true,

                cacheUpdated =
                    true,

                hasAscs =
                    true,

                hasPacs =
                    beforePacs,

                hasBass =
                    beforeBass,

                text =
                    """
                    ===== BTMicFix ANDROID LE CACHE =====
                    READY

                    Device:
                    $deviceName

                    Address:
                    $maskedAddress


                    ASCS / LE_AUDIO 0x184E:
                    YES

                    PACS 0x1850:
                    ${yesNo(beforePacs)}

                    BASS 0x184F:
                    ${yesNo(beforeBass)}


                    Android already has the
                    LE_AUDIO UUID required for
                    the LE Audio connection.

                    READY FOR LE AUDIO CONNECT.

                    =====================================
                    """.trimIndent()
            )
        }

        /*
         * ========================================================
         * GET HIDDEN IBluetooth SERVICE
         * ========================================================
         */

        val rawBluetoothService =
            try {

                HiddenApiBypass.invoke(
                    BluetoothAdapter::class.java,
                    adapter,
                    "getBluetoothService"
                )

            } catch (
                first: Throwable
            ) {

                try {

                    HiddenApiBypass.invoke(
                        adapter.javaClass,
                        adapter,
                        "getBluetoothService"
                    )

                } catch (
                    second: Throwable
                ) {

                    return failure(
                        """
                        Could not obtain Android's
                        hidden IBluetooth service.

                        First attempt:

                        ${first.javaClass.simpleName}
                        ${first.message}

                        Second attempt:

                        ${second.javaClass.simpleName}
                        ${second.message}
                        """.trimIndent()
                    )
                }
            }

        if (
            rawBluetoothService ==
            null
        ) {

            return failure(
                """
                BluetoothAdapter.getBluetoothService()
                returned NULL.
                """.trimIndent()
            )
        }

        val rawInterface =
            rawBluetoothService
                as? IInterface

        if (
            rawInterface ==
            null
        ) {

            return failure(
                """
                Unexpected Bluetooth service type:

                ${rawBluetoothService.javaClass.name}
                """.trimIndent()
            )
        }

        val rawBinder:
            IBinder =
            rawInterface.asBinder()

        if (
            !rawBinder.isBinderAlive
        ) {

            return failure(
                "Android's IBluetooth Binder is not alive."
            )
        }

        /*
         * ========================================================
         * SHIZUKU BINDER WRAPPER
         * ========================================================
         */

        val privilegedBinder =
            try {

                ShizukuBinderWrapper(
                    rawBinder
                )

            } catch (
                e: Throwable
            ) {

                return failure(
                    """
                    Shizuku Binder wrapper failed.

                    ${e.javaClass.name}

                    ${e.message}
                    """.trimIndent()
                )
            }

        /*
         * ========================================================
         * IBluetooth.Stub
         * ========================================================
         */

        val stubClass =
            try {

                Class.forName(
                    "android.bluetooth.IBluetooth\$Stub"
                )

            } catch (
                e: Throwable
            ) {

                return failure(
                    """
                    IBluetooth.Stub could not
                    be loaded.

                    ${e.javaClass.name}

                    ${e.message}
                    """.trimIndent()
                )
            }

        val privilegedService =
            try {

                HiddenApiBypass.invoke(
                    stubClass,
                    null,
                    "asInterface",
                    privilegedBinder
                )

            } catch (
                e: Throwable
            ) {

                return failure(
                    """
                    Could not create privileged
                    IBluetooth interface.

                    ${e.javaClass.name}

                    ${e.message}
                    """.trimIndent()
                )
            }

        if (
            privilegedService ==
            null
        ) {

            return failure(
                """
                IBluetooth.Stub.asInterface
                returned NULL.
                """.trimIndent()
            )
        }

        /*
         * ========================================================
         * SHELL ATTRIBUTION
         * ========================================================
         */

        val shellSource =
            try {

                AttributionSource
                    .Builder(
                        SHELL_UID
                    )
                    .setPackageName(
                        SHELL_PACKAGE
                    )
                    .build()

            } catch (
                e: Throwable
            ) {

                return failure(
                    """
                    Could not create
                    shell AttributionSource.

                    ${e.javaClass.name}

                    ${e.message}
                    """.trimIndent()
                )
            }

        /*
         * ========================================================
         * FETCH REMOTE UUIDS OVER LE
         * ========================================================
         */

        val fetchResult =
            try {

                callFetchRemoteUuids(
                    service =
                        privilegedService,

                    device =
                        device,

                    attributionSource =
                        shellSource
                )

            } catch (
                e: Throwable
            ) {

                val actual =
                    unwrap(
                        e
                    )

                return failure(
                    """
                    LE UUID refresh Binder call failed.

                    ${actual.javaClass.name}

                    ${actual.message}
                    """.trimIndent()
                )
            }

        /*
         * ========================================================
         * REQUEST REJECTED
         * ========================================================
         */

        if (
            !fetchResult.accepted
        ) {

            return RefreshResult(

                requestAccepted =
                    false,

                cacheUpdated =
                    false,

                hasAscs =
                    beforeAscs,

                hasPacs =
                    beforePacs,

                hasBass =
                    beforeBass,

                text =
                    """
                    ===== BTMicFix ANDROID LE CACHE =====
                    REQUEST REJECTED

                    Device:
                    $deviceName

                    Address:
                    $maskedAddress

                    Binder:
                    ${fetchResult.signature}

                    Android rejected the
                    LE UUID refresh request.

                    =====================================
                    """.trimIndent()
            )
        }

        /*
         * ========================================================
         * WAIT FOR 184E
         * ========================================================
         */

        val startTime =
            System.currentTimeMillis()

        var after =
            readCachedUuids(
                device
            )

        var afterAscs =
            hasUuid(
                after,
                ASCS_UUID
            )

        var afterPacs =
            hasUuid(
                after,
                PACS_UUID
            )

        var afterBass =
            hasUuid(
                after,
                BASS_UUID
            )

        while (
            System.currentTimeMillis() -
                startTime <
                CACHE_WAIT_MS
        ) {

            /*
             * ASCS 184E is the important gate.
             */

            if (
                afterAscs
            ) {

                break
            }

            try {

                Thread.sleep(
                    CACHE_POLL_MS
                )

            } catch (
                _: InterruptedException
            ) {

                break
            }

            after =
                readCachedUuids(
                    device
                )

            afterAscs =
                hasUuid(
                    after,
                    ASCS_UUID
                )

            afterPacs =
                hasUuid(
                    after,
                    PACS_UUID
                )

            afterBass =
                hasUuid(
                    after,
                    BASS_UUID
                )
        }

        /*
         * ========================================================
         * SUCCESS
         * ========================================================
         */

        if (
            afterAscs
        ) {

            return RefreshResult(

                requestAccepted =
                    true,

                cacheUpdated =
                    true,

                hasAscs =
                    true,

                hasPacs =
                    afterPacs,

                hasBass =
                    afterBass,

                text =
                    """
                    ===== BTMicFix ANDROID LE CACHE =====
                    SUCCESS

                    Device:
                    $deviceName

                    Address:
                    $maskedAddress

                    Binder:
                    ${fetchResult.signature}


                    BEFORE

                    ASCS / LE_AUDIO 0x184E:
                    ${yesNo(beforeAscs)}

                    PACS 0x1850:
                    ${yesNo(beforePacs)}

                    BASS 0x184F:
                    ${yesNo(beforeBass)}


                    AFTER

                    ASCS / LE_AUDIO 0x184E:
                    YES

                    PACS 0x1850:
                    ${yesNo(afterPacs)}

                    BASS 0x184F:
                    ${yesNo(afterBass)}


                    Android's LE_AUDIO UUID
                    cache is ready.

                    READY FOR LE AUDIO CONNECT.

                    =====================================
                    """.trimIndent()
            )
        }

        /*
         * ========================================================
         * CACHE DID NOT UPDATE
         * ========================================================
         */

        return RefreshResult(

            requestAccepted =
                true,

            cacheUpdated =
                false,

            hasAscs =
                false,

            hasPacs =
                afterPacs,

            hasBass =
                afterBass,

            text =
                """
                ===== BTMicFix ANDROID LE CACHE =====
                LE AUDIO UUID STILL MISSING

                Device:
                $deviceName

                Address:
                $maskedAddress

                Binder:
                ${fetchResult.signature}

                Android accepted the refresh
                request but ASCS / LE_AUDIO
                0x184E still did not appear.

                PACS 0x1850:
                ${yesNo(afterPacs)}

                BASS 0x184F:
                ${yesNo(afterBass)}

                =====================================
                """.trimIndent()
        )
    }

    /*
     * ============================================================
     * FETCH REMOTE UUIDS
     * ============================================================
     */

    private fun callFetchRemoteUuids(
        service: Any,
        device: BluetoothDevice,
        attributionSource: AttributionSource
    ): FetchCallResult {

        val methods =
            service
                .javaClass
                .methods
                .filter {

                    it.name ==
                        "fetchRemoteUuids" ||

                        it.name ==
                        "fetchRemoteUuidsWithAttribution"
                }

        /*
         * ========================================================
         * DIRECT 3-PARAMETER FORM
         * ========================================================
         */

        val direct =
            methods
                .firstOrNull {
                        method ->

                    val types =
                        method.parameterTypes

                    types.size ==
                        3 &&

                        types[0] ==
                        BluetoothDevice::class.java &&

                        types[1] ==
                        Int::class.javaPrimitiveType &&

                        types[2].name ==
                        "android.content.AttributionSource"
                }

        if (
            direct !=
            null
        ) {

            direct.isAccessible =
                true

            val result =
                direct.invoke(
                    service,
                    device,
                    BluetoothDevice.TRANSPORT_LE,
                    attributionSource
                )

            return FetchCallResult(

                accepted =
                    result as? Boolean
                        ?: true,

                signature =
                    "fetchRemoteUuids(" +
                        "BluetoothDevice, int, AttributionSource" +
                        ")"
            )
        }

        /*
         * ========================================================
         * 4-PARAMETER RESULT RECEIVER FORM
         * ========================================================
         */

        val receiverMethod =
            methods
                .firstOrNull {
                        method ->

                    val types =
                        method.parameterTypes

                    types.size ==
                        4 &&

                        types[0] ==
                        BluetoothDevice::class.java &&

                        types[1] ==
                        Int::class.javaPrimitiveType &&

                        types[2].name ==
                        "android.content.AttributionSource" &&

                        types[3]
                            .name
                            .contains(
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
                createSynchronousResultReceiver()

            receiverMethod.invoke(
                service,
                device,
                BluetoothDevice.TRANSPORT_LE,
                attributionSource,
                receiver
            )

            val receiverResult =
                awaitReceiverBoolean(
                    receiver
                )

            return FetchCallResult(

                accepted =
                    receiverResult
                        ?: true,

                signature =
                    "fetchRemoteUuids(" +
                        "BluetoothDevice, int, " +
                        "AttributionSource, " +
                        "SynchronousResultReceiver" +
                        ")"
            )
        }

        /*
         * ========================================================
         * NO MATCH
         * ========================================================
         */

        val signatures =
            if (
                methods.isEmpty()
            ) {

                "NONE"

            } else {

                methods.joinToString(
                    separator = "\n"
                ) {
                        method ->

                    method.name +
                        "(" +
                        method
                            .parameterTypes
                            .joinToString(
                                ", "
                            ) {
                                it.simpleName
                            } +
                        ") -> " +
                        method
                            .returnType
                            .simpleName
                }
            }

        throw NoSuchMethodException(
            """
            No supported
            fetchRemoteUuids method found.

            Methods:

            $signatures
            """.trimIndent()
        )
    }

    /*
     * ============================================================
     * CREATE RESULT RECEIVER
     * ============================================================
     */

    private fun createSynchronousResultReceiver():
        Any {

        val receiverClass =
            Class.forName(
                "com.android.modules.utils.SynchronousResultReceiver"
            )

        val getMethod =
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

        getMethod.isAccessible =
            true

        return getMethod
            .invoke(
                null
            )
            ?: throw IllegalStateException(
                "SynchronousResultReceiver.get() returned null"
            )
    }

    /*
     * ============================================================
     * WAIT FOR BOOLEAN RECEIVER
     * ============================================================
     */

    private fun awaitReceiverBoolean(
        receiver: Any
    ): Boolean? {

        return try {

            val awaitMethod =
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

            awaitMethod.isAccessible =
                true

            val parameter =
                awaitMethod
                    .parameterTypes[0]

            val timeoutArgument:
                Any =
                when {

                    parameter ==
                        Duration::class.java ->

                        Duration.ofSeconds(
                            RECEIVER_WAIT_SECONDS
                        )

                    parameter ==
                        Long::class.javaPrimitiveType ->

                        RECEIVER_WAIT_SECONDS *
                            1000L

                    parameter ==
                        java.lang.Long::class.java ->

                        RECEIVER_WAIT_SECONDS *
                            1000L

                    else ->

                        return null
                }

            val resultObject =
                awaitMethod.invoke(
                    receiver,
                    timeoutArgument
                )
                    ?: return null

            val getValue =
                resultObject
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

            getValue.invoke(
                resultObject,
                false
            ) as? Boolean

        } catch (
            e: InvocationTargetException
        ) {

            throw (
                e.targetException
                    ?: e
                )

        } catch (
            e: Throwable
        ) {

            Logger.w(
                "Could not read UUID refresh receiver: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )

            /*
             * The Binder request may still have been sent.
             *
             * The polling logic will verify whether it worked.
             */

            null
        }
    }

    /*
     * ============================================================
     * READ ANDROID CACHED UUIDS
     * ============================================================
     */

    private fun readCachedUuids(
        device: BluetoothDevice
    ): List<String> {

        return try {

            device
                .uuids
                ?.map {

                    it.uuid
                        .toString()
                        .lowercase()
                }
                ?.distinct()
                ?.sorted()
                ?: emptyList()

        } catch (
            _: Throwable
        ) {

            emptyList()
        }
    }

    /*
     * ============================================================
     * UUID CHECK
     * ============================================================
     */

    private fun hasUuid(
        uuids: List<String>,
        target: String
    ): Boolean {

        return uuids.any {

            it.equals(
                target,
                ignoreCase =
                    true
            )
        }
    }

    /*
     * ============================================================
     * FIND BUDS
     * ============================================================
     */

    private fun findBestMatchingDevice(
        devices: List<BluetoothDevice>,
        preferredName: String
    ): BluetoothDevice? {

        val wanted =
            preferredName.trim()

        /*
         * Exact alias.
         */

        devices
            .firstOrNull {
                    device ->

                val alias =
                    try {

                        device.alias

                    } catch (
                        _: Throwable
                    ) {

                        null
                    }

                alias?.equals(
                    wanted,
                    ignoreCase =
                        true
                ) == true
            }
            ?.let {

                return it
            }

        /*
         * Exact Bluetooth name.
         */

        devices
            .firstOrNull {
                    device ->

                val name =
                    try {

                        device.name

                    } catch (
                        _: Throwable
                    ) {

                        null
                    }

                name?.equals(
                    wanted,
                    ignoreCase =
                        true
                ) == true
            }
            ?.let {

                return it
            }

        /*
         * Buds4 Pro.
         */

        val buds4 =
            devices.filter {

                safeDeviceLabel(
                    it
                )
                    .contains(
                        "Buds4 Pro",
                        ignoreCase =
                            true
                    )
            }

        if (
            buds4.size ==
            1
        ) {

            return buds4.first()
        }

        /*
         * Generic Buds + Pro.
         */

        val budsPro =
            devices.filter {

                val label =
                    safeDeviceLabel(
                        it
                    )

                label.contains(
                    "Buds",
                    ignoreCase =
                        true
                ) &&
                    label.contains(
                        "Pro",
                        ignoreCase =
                            true
                    )
            }

        if (
            budsPro.size ==
            1
        ) {

            return budsPro.first()
        }

        return null
    }

    /*
     * ============================================================
     * DEVICE LABEL
     * ============================================================
     */

    private fun safeDeviceLabel(
        device: BluetoothDevice
    ): String {

        val alias =
            try {

                device.alias

            } catch (
                _: Throwable
            ) {

                null
            }

        val name =
            try {

                device.name

            } catch (
                _: Throwable
            ) {

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

    /*
     * ============================================================
     * MASK MAC
     * ============================================================
     */

    private fun maskAddress(
        address: String
    ): String {

        if (
            address.length <
            5
        ) {

            return "REDACTED"
        }

        return "XX:XX:XX:XX:" +
            address.takeLast(
                5
            )
    }

    /*
     * ============================================================
     * UNWRAP REFLECTION
     * ============================================================
     */

    private fun unwrap(
        throwable: Throwable
    ): Throwable {

        return if (
            throwable is
            InvocationTargetException
        ) {

            throwable.targetException
                ?: throwable

        } else {

            throwable
        }
    }

    /*
     * ============================================================
     * YES / NO
     * ============================================================
     */

    private fun yesNo(
        value: Boolean
    ): String {

        return if (
            value
        ) {

            "YES"

        } else {

            "NO"
        }
    }

    /*
     * ============================================================
     * FAILURE
     * ============================================================
     */

    private fun failure(
        details: String
    ): RefreshResult {

        return RefreshResult(

            requestAccepted =
                false,

            cacheUpdated =
                false,

            hasAscs =
                false,

            hasPacs =
                false,

            hasBass =
                false,

            text =
                """
                ===== BTMicFix ANDROID LE CACHE =====
                FAILED

                $details

                =====================================
                """.trimIndent()
        )
    }

    /*
     * ============================================================
     * INTERNAL RESULT
     * ============================================================
     */

    private data class FetchCallResult(

        val accepted:
            Boolean,

        val signature:
            String
    )
}
