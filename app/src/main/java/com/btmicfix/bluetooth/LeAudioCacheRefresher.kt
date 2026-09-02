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
 * IMPORTANT:
 *
 * The previous build incorrectly required BOTH:
 *
 * ASCS 0x184E
 * PACS 0x1850
 *
 * before allowing LE Audio connection.
 *
 * AOSP LeAudioService.connect() actually checks only whether
 * BluetoothUuid.LE_AUDIO is present in Android's remote UUID cache.
 *
 * On this Fold6, the transport-specific refresh produced:
 *
 * ASCS 0x184E = YES
 * BASS 0x184F = YES
 * PACS 0x1850 = NO
 *
 * Live GATT separately proved PACS 0x1850 really exists.
 *
 * Therefore:
 *
 * ASCS 0x184E in Android cache = READY FOR LE AUDIO CONNECT.
 *
 * PACS remains diagnostic information only.
 */
object LeAudioCacheRefresher {

    private const val SHELL_UID =
        2000

    private const val SHELL_PACKAGE =
        "com.android.shell"

    /*
     * Android's LE_AUDIO UUID gate.
     */
    private const val ASCS_UUID =
        "0000184e-0000-1000-8000-00805f9b34fb"

    /*
     * Useful diagnostics only.
     */
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

    data class RefreshResult(
        val requestAccepted: Boolean,

        /*
         * IMPORTANT:
         *
         * cacheUpdated now means:
         *
         * Android has ASCS / LE_AUDIO UUID 0x184E.
         *
         * PACS is NOT required.
         */
        val cacheUpdated: Boolean,

        val hasAscs: Boolean,
        val hasPacs: Boolean,
        val hasBass: Boolean,

        val text: String
    )

    fun refresh(
        context: Context,
        preferredDeviceName: String
    ): RefreshResult {

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

            if (!Shizuku.pingBinder()) {

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

        } catch (e: Throwable) {

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

            HiddenApiBypass.setHiddenApiExemptions(
                "Landroid/bluetooth/",
                "Lcom/android/modules/utils/"
            )

        } catch (e: Throwable) {

            Logger.w(
                "Hidden API exemption error: " +
                    "${e.javaClass.simpleName}: ${e.message}"
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

        if (manager == null) {

            return failure(
                "BluetoothManager is unavailable."
            )
        }

        val adapter =
            try {

                manager.adapter

            } catch (e: Throwable) {

                return failure(
                    """
                    BluetoothAdapter access failed.

                    ${e.javaClass.name}

                    ${e.message}
                    """.trimIndent()
                )
            }

        if (adapter == null) {

            return failure(
                "BluetoothAdapter returned NULL."
            )
        }

        val enabled =
            try {

                adapter.isEnabled

            } catch (_: Throwable) {

                false
            }

        if (!enabled) {

            return failure(
                "Bluetooth is OFF."
            )
        }

        /*
         * ========================================================
         * FIND REAL PAIRED BUDS
         * ========================================================
         */

        val bondedDevices =
            try {

                adapter
                    .bondedDevices
                    .toList()

            } catch (e: Throwable) {

                return failure(
                    """
                    Could not read paired Bluetooth devices.

                    ${e.javaClass.name}

                    ${e.message}
                    """.trimIndent()
                )
            }

        val device =
            findBestMatchingDevice(
                devices =
                    bondedDevices,

                preferredName =
                    preferredDeviceName
            )

        if (device == null) {

            return failure(
                """
                Could not identify:

                $preferredDeviceName

                in Android's paired Bluetooth devices.
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
                } catch (_: Throwable) {
                    ""
                }
            )

        /*
         * ========================================================
         * READ CACHE BEFORE REFRESH
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
         * IMPORTANT CHANGE
         * ========================================================
         *
         * If 184E is already cached, we are DONE.
         *
         * Do NOT wait for PACS.
         *
         * Android LeAudioService.connect() checks LE_AUDIO / 184E.
         */

        if (beforeAscs) {

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


                    ANDROID CACHE

                    ASCS / LE_AUDIO 0x184E:
                    YES

                    PACS 0x1850:
                    ${yesNo(beforePacs)}

                    BASS 0x184F:
                    ${yesNo(beforeBass)}


                    IMPORTANT:

                    Android now has the LE_AUDIO
                    UUID required by LeAudioService.

                    PACS 0x1850 is NOT required
                    in this cached UUID list for
                    LeAudioService.connect().

                    Live GATT already proved that
                    PACS exists on the Buds.

                    READY FOR LE AUDIO CONNECT.

                    =====================================
                    """.trimIndent()
            )
        }

        /*
         * ========================================================
         * GET IBluetooth SERVICE
         * ========================================================
         */

        val rawBluetoothService =
            try {

                HiddenApiBypass.invoke(
                    BluetoothAdapter::class.java,
                    adapter,
                    "getBluetoothService"
                )

            } catch (first: Throwable) {

                try {

                    HiddenApiBypass.invoke(
                        adapter.javaClass,
                        adapter,
                        "getBluetoothService"
                    )

                } catch (second: Throwable) {

                    return failure(
                        """
                        Could not obtain Android's
                        hidden IBluetooth service.

                        First:
                        ${first.javaClass.simpleName}
                        ${first.message}

                        Second:
                        ${second.javaClass.simpleName}
                        ${second.message}
                        """.trimIndent()
                    )
                }
            }

        if (rawBluetoothService == null) {

            return failure(
                """
                BluetoothAdapter.getBluetoothService()
                returned NULL.
                """.trimIndent()
            )
        }

        val rawInterface =
            rawBluetoothService as? IInterface

        if (rawInterface == null) {

            return failure(
                """
                Unexpected Bluetooth service type:

                ${rawBluetoothService.javaClass.name}
                """.trimIndent()
            )
        }

        val rawBinder: IBinder =
            rawInterface.asBinder()

        if (!rawBinder.isBinderAlive) {

            return failure(
                "Android's IBluetooth Binder is not alive."
            )
        }

        /*
         * ========================================================
         * WRAP BINDER THROUGH SHIZUKU
         * ========================================================
         */

        val privilegedBinder =
            try {

                ShizukuBinderWrapper(
                    rawBinder
                )

            } catch (e: Throwable) {

                return failure(
                    """
                    Shizuku Binder wrapper failed.

                    ${e.javaClass.name}

                    ${e.message}
                    """.trimIndent()
                )
            }

        val stubClass =
            try {

                Class.forName(
                    "android.bluetooth.IBluetooth\$Stub"
                )

            } catch (e: Throwable) {

                return failure(
                    """
                    IBluetooth.Stub could not be loaded.

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

            } catch (e: Throwable) {

                return failure(
                    """
                    Could not create privileged
                    IBluetooth interface.

                    ${e.javaClass.name}

                    ${e.message}
                    """.trimIndent()
                )
            }

        if (privilegedService == null) {

            return failure(
                "IBluetooth.Stub.asInterface returned NULL."
            )
        }

        /*
         * ========================================================
         * SHELL ATTRIBUTION
         * ========================================================
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

            } catch (e: Throwable) {

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

        if (!fetchResult.accepted) {

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

                    Binder method:
                    ${fetchResult.signature}

                    Android rejected the
                    LE UUID refresh request.

                    =====================================
                    """.trimIndent()
            )
        }

        /*
         * ========================================================
         * WAIT ONLY FOR 184E
         * ========================================================
         *
         * THIS IS THE KEY CHANGE.
         *
         * Previously:
         *
         * wait for 184E && 1850
         *
         * Now:
         *
         * wait for 184E
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
             * 184E alone means Android's LE Audio
             * connection gate can now succeed.
             */
            if (afterAscs) {

                break
            }

            try {

                Thread.sleep(
                    CACHE_POLL_MS
                )

            } catch (_: InterruptedException) {

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
         * SUCCESS WHEN 184E APPEARS
         * ========================================================
         */

        if (afterAscs) {

            return RefreshResult(
                requestAccepted =
                    true,

                /*
                 * Cache is sufficiently ready for LE Audio.
                 */
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
                    SUCCESS - LE AUDIO UUID READY

                    Device:
                    $deviceName

                    Address:
                    $maskedAddress

                    Binder method:
                    ${fetchResult.signature}

                    LE refresh request:
                    ACCEPTED


                    BEFORE CACHE

                    ASCS / LE_AUDIO 0x184E:
                    ${yesNo(beforeAscs)}

                    PACS 0x1850:
                    ${yesNo(beforePacs)}

                    BASS 0x184F:
                    ${yesNo(beforeBass)}


                    AFTER CACHE

                    ASCS / LE_AUDIO 0x184E:
                    YES

                    PACS 0x1850:
                    ${yesNo(afterPacs)}

                    BASS 0x184F:
                    ${yesNo(afterBass)}


                    Android now contains the UUID
                    required by LeAudioService.connect().

                    PACS does not need to appear in
                    BluetoothDevice.getUuids() for the
                    LE Audio connection gate.

                    READY FOR LE AUDIO CONNECT.

                    =====================================
                    """.trimIndent()
            )
        }

        /*
         * ========================================================
         * REAL FAILURE
         * ========================================================
         *
         * Only fail if 184E is STILL missing.
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

                Binder method:
                ${fetchResult.signature}

                Android accepted the refresh request,
                but ASCS / LE_AUDIO 0x184E still did
                not appear within
                ${CACHE_WAIT_MS / 1000} seconds.

                PACS 0x1850:
                ${yesNo(afterPacs)}

                BASS 0x184F:
                ${yesNo(afterBass)}

                No LE Audio profile connection
                should be attempted.

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
         * --------------------------------------------------------
         * DIRECT SAMSUNG / ANDROID FORM
         * --------------------------------------------------------
         *
         * This is the form your Fold6 already proved works:
         *
         * fetchRemoteUuids(
         *     BluetoothDevice,
         *     int,
         *     AttributionSource
         * )
         */

        val directMethod =
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

        if (
            directMethod !=
            null
        ) {

            directMethod.isAccessible =
                true

            val response =
                directMethod.invoke(
                    service,
                    device,
                    BluetoothDevice.TRANSPORT_LE,
                    attributionSource
                )

            return FetchCallResult(
                accepted =
                    response as? Boolean
                        ?: true,

                signature =
                    "fetchRemoteUuids(" +
                        "BluetoothDevice, int, AttributionSource" +
                        ")"
            )
        }

        /*
         * --------------------------------------------------------
         * RESULT RECEIVER FORM
         * --------------------------------------------------------
         */

        val receiverMethod =
            methods.firstOrNull { method ->

                val types =
                    method.parameterTypes

                types.size == 4 &&

                    types[0] ==
                        BluetoothDevice::class.java &&

                    types[1] ==
                        Int::class.javaPrimitiveType &&

                    types[2].name ==
                        "android.content.AttributionSource" &&

                    types[3].name.contains(
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

            val result =
                awaitReceiverBoolean(
                    receiver
                )

            return FetchCallResult(
                accepted =
                    result ?: true,

                signature =
                    "fetchRemoteUuids(" +
                        "BluetoothDevice, int, " +
                        "AttributionSource, " +
                        "SynchronousResultReceiver" +
                        ")"
            )
        }

        val signatures =
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
            No supported fetchRemoteUuids
            method found.

            Samsung methods:

            $signatures
            """.trimIndent()
        )
    }

    /*
     * ============================================================
     * SYNCHRONOUS RESULT RECEIVER
     * ============================================================
     */

    private fun createSynchronousResultReceiver():
        Any {

        val receiverClass =
            Class.forName(
                "com.android.modules.utils.SynchronousResultReceiver"
            )

        val method =
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

        method.isAccessible =
            true

        return method.invoke(
            null
        )
            ?: throw IllegalStateException(
                "SynchronousResultReceiver.get() returned null"
            )
    }

    private fun awaitReceiverBoolean(
        receiver: Any
    ): Boolean? {

        return try {

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

            val type =
                await.parameterTypes[0]

            val argument: Any =
                when {

                    type ==
                        Duration::class.java ->

                        Duration.ofSeconds(
                            RECEIVER_WAIT_SECONDS
                        )

                    type ==
                        Long::class.javaPrimitiveType ||

                    type ==
                        java.lang.Long::class.java ->

                        RECEIVER_WAIT_SECONDS *
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

            getValue.invoke(
                result,
                false
            ) as? Boolean

        } catch (
            e: InvocationTargetException
        ) {

            throw (
                e.targetException
                    ?: e
                )

        } catch (e: Throwable) {

            Logger.w(
                "Receiver result unavailable: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )

            null
        }
    }

    /*
     * ============================================================
     * CACHE HELPERS
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

        } catch (_: Throwable) {

            emptyList()
        }
    }

    private fun hasUuid(
        uuids: List<String>,
        uuid: String
    ): Boolean {

        return uuids.any {

            it.equals(
                uuid,
                ignoreCase = true
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

        devices
            .firstOrNull { device ->

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
            }
            ?.let {

                return it
            }

        devices
            .firstOrNull { device ->

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
            }
            ?.let {

                return it
            }

        val buds4 =
            devices.filter {

                safeDeviceLabel(it)
                    .contains(
                        "Buds4 Pro",
                        ignoreCase = true
                    )
            }

        if (
            buds4.size ==
            1
        ) {

            return buds4.first()
        }

        val budsPro =
            devices.filter {

                val label =
                    safeDeviceLabel(it)

                label.contains(
                    "Buds",
                    ignoreCase = true
                ) &&
                    label.contains(
                        "Pro",
                        ignoreCase = true
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

    private fun unwrap(
        throwable: Throwable
    ): Throwable {

        return if (
            throwable is InvocationTargetException
        ) {

            throwable.targetException
                ?: throwable

        } else {

            throwable
        }
    }

    private fun yesNo(
        value: Boolean
    ): String {

        return if (value) {
            "YES"
        } else {
            "NO"
        }
    }

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

    private data class FetchCallResult(
        val accepted: Boolean,
        val signature: String
    )
}
