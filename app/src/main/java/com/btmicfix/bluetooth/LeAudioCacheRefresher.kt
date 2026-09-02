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
import java.lang.reflect.Method
import java.time.Duration

/**
 * Refreshes Android's cached Bluetooth UUID information specifically
 * over the Bluetooth LE transport.
 *
 * WHY THIS EXISTS:
 *
 * Live GATT discovery on Antonio's Buds4 Pro proved:
 *
 * ASCS 0x184E = YES
 * PACS 0x1850 = YES
 * BASS 0x184F = YES
 *
 * But BluetoothDevice.getUuids() did NOT contain 184E / 1850.
 *
 * Android's LE Audio profile checks Android's own Bluetooth UUID cache.
 *
 * This class asks Android's Bluetooth stack to perform its own
 * transport-specific LE service discovery so the framework cache
 * can be updated properly.
 *
 *
 * IMPORTANT:
 *
 * This class does NOT:
 *
 * - modify LE Audio connection policy
 * - disconnect HFP
 * - disconnect A2DP
 * - connect the LE Audio profile
 * - change AudioManager routing
 *
 * It ONLY requests:
 *
 * fetchRemoteUuids(
 *     device,
 *     TRANSPORT_LE
 * )
 *
 * through a Shizuku-wrapped Bluetooth Binder.
 */
object LeAudioCacheRefresher {

    /*
     * ============================================================
     * CONSTANTS
     * ============================================================
     */

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

    /*
     * Android's LE UUID discovery is asynchronous.
     *
     * A generous timeout gives Samsung's Bluetooth stack time to
     * complete GATT service discovery and update its RemoteDevices cache.
     */
    private const val CACHE_WAIT_MS =
        20000L

    private const val CACHE_POLL_MS =
        500L

    private const val RECEIVER_WAIT_SECONDS =
        5L

    /*
     * ============================================================
     * RESULT
     * ============================================================
     */

    data class RefreshResult(
        val requestAccepted: Boolean,
        val cacheUpdated: Boolean,
        val hasAscs: Boolean,
        val hasPacs: Boolean,
        val hasBass: Boolean,
        val text: String
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
         * --------------------------------------------------------
         * BLUETOOTH PERMISSION
         * --------------------------------------------------------
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
         * --------------------------------------------------------
         * SHIZUKU
         * --------------------------------------------------------
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
         * --------------------------------------------------------
         * HIDDEN API EXEMPTION
         * --------------------------------------------------------
         *
         * HiddenApiBypass only lets us reach Android's hidden Java
         * framework interfaces.
         *
         * The actual privileged Binder call still goes through
         * Shizuku.
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
         * NORMAL BLUETOOTH ADAPTER
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

            val paired =
                bondedDevices
                    .joinToString(
                        separator = "\n"
                    ) {

                        safeDeviceLabel(it)
                    }

            return failure(
                """
                Could not identify Antonio's Buds4 Pro.

                Selected:
                $preferredDeviceName

                Paired devices:

                $paired
                """.trimIndent()
            )
        }

        val deviceName =
            safeDeviceLabel(
                device
            )

        val address =
            maskAddress(
                try {
                    device.address
                } catch (_: Throwable) {
                    ""
                }
            )

        /*
         * ========================================================
         * CACHE BEFORE
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
         * If Android already has the required LE Audio UUIDs,
         * there is nothing to refresh.
         */

        if (
            beforeAscs &&
            beforePacs
        ) {

            return RefreshResult(
                requestAccepted =
                    true,

                cacheUpdated =
                    true,

                hasAscs =
                    true,

                hasPacs =
                    true,

                hasBass =
                    beforeBass,

                text =
                    """
                    ===== BTMicFix ANDROID LE CACHE =====
                    CACHE ALREADY READY

                    Device:
                    $deviceName

                    Address:
                    $address

                    BEFORE CACHE:

                    ASCS 0x184E:
                    YES

                    PACS 0x1850:
                    YES

                    BASS 0x184F:
                    ${yesNo(beforeBass)}

                    Android already has the core
                    LE Audio UUIDs in its cache.

                    No UUID refresh was necessary.

                    =====================================
                    """.trimIndent()
            )
        }

        /*
         * ========================================================
         * GET REAL IBluetooth SERVICE
         * ========================================================
         *
         * BluetoothAdapter internally talks to IBluetooth.
         *
         * We obtain that existing service interface from the normal
         * app process, then wrap only its Binder with Shizuku.
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
                        Could not obtain Android's hidden
                        IBluetooth service.

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
                Android returned an unexpected Bluetooth
                service object.

                Type:
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
         * WRAP IBluetooth WITH SHIZUKU
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
                    Could not create Shizuku Binder wrapper.

                    ${e.javaClass.name}

                    ${e.message}
                    """.trimIndent()
                )
            }

        /*
         * Recreate:
         *
         * android.bluetooth.IBluetooth
         *
         * around the Shizuku-wrapped Binder.
         */

        val stubClass =
            try {

                Class.forName(
                    "android.bluetooth.IBluetooth\$Stub"
                )

            } catch (e: Throwable) {

                return failure(
                    """
                    Could not load IBluetooth.Stub.

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
                    Could not create Shizuku-wrapped
                    IBluetooth interface.

                    ${e.javaClass.name}

                    ${e.message}
                    """.trimIndent()
                )
            }

        if (privilegedService == null) {

            return failure(
                """
                IBluetooth.Stub.asInterface()
                returned NULL.
                """.trimIndent()
            )
        }

        /*
         * ========================================================
         * SHELL ATTRIBUTION SOURCE
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

            } catch (e: Throwable) {

                return failure(
                    """
                    Could not create shell AttributionSource.

                    ${e.javaClass.name}

                    ${e.message}
                    """.trimIndent()
                )
            }

        /*
         * ========================================================
         * FETCH UUIDS SPECIFICALLY OVER LE
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
                    $address

                    Binder method:
                    ${fetchResult.signature}

                    Android rejected the transport-specific
                    LE UUID refresh request.

                    BEFORE CACHE:

                    ASCS 0x184E:
                    ${yesNo(beforeAscs)}

                    PACS 0x1850:
                    ${yesNo(beforePacs)}

                    BASS 0x184F:
                    ${yesNo(beforeBass)}

                    No LE Audio connect request was issued.

                    =====================================
                    """.trimIndent()
            )
        }

        /*
         * ========================================================
         * WAIT FOR ANDROID'S CACHE TO UPDATE
         * ========================================================
         *
         * fetchRemoteUuids() is asynchronous.
         *
         * We repeatedly ask BluetoothDevice.getUuids() until the
         * LE Audio UUIDs appear or the timeout expires.
         */

        val start =
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
            start <
            CACHE_WAIT_MS
        ) {

            if (
                afterAscs &&
                afterPacs
            ) {

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
         * SUCCESS
         * ========================================================
         */

        if (
            afterAscs &&
            afterPacs
        ) {

            val afterText =
                uuidListText(
                    after
                )

            return RefreshResult(
                requestAccepted =
                    true,

                cacheUpdated =
                    true,

                hasAscs =
                    true,

                hasPacs =
                    true,

                hasBass =
                    afterBass,

                text =
                    """
                    ===== BTMicFix ANDROID LE CACHE =====
                    SUCCESS

                    Device:
                    $deviceName

                    Address:
                    $address

                    Binder method:
                    ${fetchResult.signature}

                    LE refresh request:
                    ACCEPTED


                    BEFORE CACHE

                    ASCS 0x184E:
                    ${yesNo(beforeAscs)}

                    PACS 0x1850:
                    ${yesNo(beforePacs)}

                    BASS 0x184F:
                    ${yesNo(beforeBass)}


                    AFTER CACHE

                    ASCS 0x184E:
                    YES

                    PACS 0x1850:
                    YES

                    BASS 0x184F:
                    ${yesNo(afterBass)}


                    Android's own Bluetooth UUID cache
                    now contains the core LE Audio services.

                    It is now safe for BTMicFix to attempt
                    the LE Audio profile connection.


                    CACHED UUIDS AFTER REFRESH:

                    $afterText

                    =====================================
                    """.trimIndent()
            )
        }

        /*
         * ========================================================
         * REQUEST ACCEPTED, BUT CACHE DID NOT CHANGE
         * ========================================================
         */

        val afterText =
            uuidListText(
                after
            )

        return RefreshResult(
            requestAccepted =
                true,

            cacheUpdated =
                false,

            hasAscs =
                afterAscs,

            hasPacs =
                afterPacs,

            hasBass =
                afterBass,

            text =
                """
                ===== BTMicFix ANDROID LE CACHE =====
                REFRESH DID NOT COMPLETE

                Device:
                $deviceName

                Address:
                $address

                Binder method:
                ${fetchResult.signature}

                Android ACCEPTED the transport-specific
                LE UUID refresh request.

                But the framework cache did not contain
                both core LE Audio UUIDs within
                ${CACHE_WAIT_MS / 1000} seconds.


                BEFORE CACHE

                ASCS 0x184E:
                ${yesNo(beforeAscs)}

                PACS 0x1850:
                ${yesNo(beforePacs)}


                AFTER CACHE

                ASCS 0x184E:
                ${yesNo(afterAscs)}

                PACS 0x1850:
                ${yesNo(afterPacs)}

                BASS 0x184F:
                ${yesNo(afterBass)}


                No LE Audio profile connection was
                attempted after this result.

                Current cached UUIDs:

                $afterText

                =====================================
                """.trimIndent()
        )
    }

    /*
     * ============================================================
     * CALL fetchRemoteUuids()
     * ============================================================
     *
     * Android has used more than one hidden Binder signature.
     *
     * We detect the exact signature at runtime.
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
         * MODERN AIDL FORM
         * --------------------------------------------------------
         *
         * fetchRemoteUuids(
         *     BluetoothDevice,
         *     int transport,
         *     AttributionSource,
         *     SynchronousResultReceiver
         * )
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

            /*
             * The Binder method itself is oneway/void.
             *
             * Android places the Boolean result inside the
             * SynchronousResultReceiver.
             */

            val receiverAnswer =
                awaitReceiverBoolean(
                    receiver
                )

            return FetchCallResult(
                accepted =
                    receiverAnswer
                        ?: true,

                signature =
                    receiverMethod.name +
                        "(" +
                        "BluetoothDevice, int, " +
                        "AttributionSource, " +
                        "SynchronousResultReceiver" +
                        ")"
            )
        }

        /*
         * --------------------------------------------------------
         * DIRECT BOOLEAN FORM
         * --------------------------------------------------------
         *
         * Some Android/Samsung Bluetooth builds expose:
         *
         * fetchRemoteUuids(
         *     BluetoothDevice,
         *     int,
         *     AttributionSource
         * ) -> boolean
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

            val accepted =
                response as? Boolean
                    ?: true

            return FetchCallResult(
                accepted =
                    accepted,

                signature =
                    directMethod.name +
                        "(" +
                        "BluetoothDevice, int, " +
                        "AttributionSource" +
                        ")"
            )
        }

        /*
         * --------------------------------------------------------
         * NO SUPPORTED SIGNATURE
         * --------------------------------------------------------
         */

        val signatures =
            if (
                methods.isEmpty()
            ) {

                "NONE"

            } else {

                methods
                    .joinToString(
                        separator = "\n"
                    ) { method ->

                        method.name +
                            "(" +
                            method.parameterTypes
                                .joinToString(
                                    separator = ", "
                                ) {

                                    it.simpleName
                                } +
                            ") -> " +
                            method.returnType.simpleName
                    }
            }

        throw NoSuchMethodException(
            """
            No supported transport-specific
            fetchRemoteUuids method was found.

            Methods exposed by this Samsung build:

            $signatures
            """.trimIndent()
        )
    }

    /*
     * ============================================================
     * CREATE SynchronousResultReceiver
     * ============================================================
     */

    private fun createSynchronousResultReceiver():
        Any {

        val receiverClass =
            Class.forName(
                "com.android.modules.utils.SynchronousResultReceiver"
            )

        /*
         * Current Android uses:
         *
         * SynchronousResultReceiver.get()
         */

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

        return getMethod.invoke(
            null
        )
            ?: throw IllegalStateException(
                "SynchronousResultReceiver.get() returned null"
            )
    }

    /*
     * ============================================================
     * WAIT FOR RECEIVER BOOLEAN
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

            val parameterType =
                awaitMethod
                    .parameterTypes[0]

            val timeoutArgument: Any =
                when {

                    parameterType ==
                        Duration::class.java ->

                        Duration.ofSeconds(
                            RECEIVER_WAIT_SECONDS
                        )

                    parameterType ==
                        Long::class.javaPrimitiveType ||

                    parameterType ==
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

            val getValueMethod =
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

            getValueMethod.isAccessible =
                true

            getValueMethod.invoke(
                resultObject,
                false
            ) as? Boolean

        } catch (
            e: InvocationTargetException
        ) {

            val actual =
                e.targetException
                    ?: e

            throw actual

        } catch (e: Throwable) {

            Logger.w(
                "Could not read SynchronousResultReceiver result: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )

            /*
             * The Binder transaction was still sent.
             *
             * Polling the UUID cache below determines whether
             * it actually succeeded.
             */

            null
        }
    }

    /*
     * ============================================================
     * READ CACHED UUIDS
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
                ignoreCase = true
            )
        }
    }

    /*
     * ============================================================
     * FIND PAIRED BUDS
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

        /*
         * Exact Bluetooth name.
         */

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

        /*
         * Buds4 Pro.
         */

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

        /*
         * Generic Buds Pro.
         */

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

    /*
     * ============================================================
     * DEVICE NAME
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

    /*
     * ============================================================
     * MASK ADDRESS
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
     * UUID LIST TEXT
     * ============================================================
     */

    private fun uuidListText(
        uuids: List<String>
    ): String {

        return if (
            uuids.isEmpty()
        ) {

            "NONE"

        } else {

            uuids.joinToString(
                separator = "\n"
            )
        }
    }

    /*
     * ============================================================
     * UNWRAP REFLECTION ERROR
     * ============================================================
     */

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

    /*
     * ============================================================
     * YES / NO
     * ============================================================
     */

    private fun yesNo(
        value: Boolean
    ): String {

        return if (value) {
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
     * INTERNAL FETCH RESULT
     * ============================================================
     */

    private data class FetchCallResult(
        val accepted: Boolean,
        val signature: String
    )
}
