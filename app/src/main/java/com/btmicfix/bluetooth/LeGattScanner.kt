package com.btmicfix.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.btmicfix.util.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Safe LE Audio GATT diagnostic.
 *
 * This DOES NOT:
 *
 * - change Bluetooth profile policies
 * - disable HFP
 * - disable A2DP
 * - call BluetoothLeAudio.connect()
 * - force TYPE_BLE_HEADSET
 *
 * It only:
 *
 * 1. Finds the real paired Buds4 Pro BluetoothDevice.
 * 2. Opens a temporary GATT connection using TRANSPORT_LE.
 * 3. Runs discoverServices().
 * 4. Checks for important LE Audio GATT services.
 * 5. Closes the temporary GATT client.
 *
 *
 * Important LE Audio services:
 *
 * 0x184E = Audio Stream Control Service (ASCS)
 * 0x1850 = Published Audio Capabilities Service (PACS)
 * 0x184F = Broadcast Audio Scan Service (BASS)
 */
object LeGattScanner {

    private const val SCAN_TIMEOUT_SECONDS =
        15L

    private const val ASCS_UUID =
        "0000184e-0000-1000-8000-00805f9b34fb"

    private const val PACS_UUID =
        "00001850-0000-1000-8000-00805f9b34fb"

    private const val BASS_UUID =
        "0000184f-0000-1000-8000-00805f9b34fb"

    data class ScanResult(
        val success: Boolean,
        val hasAscs: Boolean,
        val hasPacs: Boolean,
        val hasBass: Boolean,
        val text: String
    )

    fun scan(
        context: Context,
        preferredDeviceName: String
    ): ScanResult {

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
         * BLUETOOTH MANAGER / ADAPTER
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
                """
                BluetoothManager is unavailable.
                """.trimIndent()
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
         * FIND THE REAL BONDED BUDS
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
                bondedDevices,
                preferredDeviceName
            )

        if (device == null) {

            val names =
                bondedDevices
                    .joinToString("\n") {
                        safeDeviceLabel(it)
                    }

            return failure(
                """
                Could not match:

                $preferredDeviceName

                to a paired Bluetooth device.

                Paired devices:

                $names
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
         * CACHED UUIDS
         * ========================================================
         *
         * These are the UUIDs Android already had cached.
         *
         * We show them only for comparison with the live
         * GATT-discovered services.
         */

        val cachedUuids =
            try {

                device.uuids
                    ?.map {
                        it.uuid
                            .toString()
                            .lowercase()
                    }
                    ?: emptyList()

            } catch (_: Throwable) {

                emptyList()
            }

        /*
         * ========================================================
         * GATT SCAN STATE
         * ========================================================
         */

        val resultReference =
            AtomicReference<ScanResult?>(
                null
            )

        val gattReference =
            AtomicReference<BluetoothGatt?>(
                null
            )

        val finished =
            AtomicBoolean(
                false
            )

        val latch =
            CountDownLatch(
                1
            )

        /*
         * ========================================================
         * FINISH HELPER
         * ========================================================
         */

        fun finish(
            result: ScanResult
        ) {

            if (
                finished.compareAndSet(
                    false,
                    true
                )
            ) {

                resultReference.set(
                    result
                )

                latch.countDown()
            }
        }

        /*
         * ========================================================
         * GATT CALLBACK
         * ========================================================
         */

        val callback =
            object :
                BluetoothGattCallback() {

                override fun onConnectionStateChange(
                    gatt: BluetoothGatt,
                    status: Int,
                    newState: Int
                ) {

                    Logger.i(
                        "LE GATT state change: " +
                            "status=$status " +
                            "state=$newState"
                    )

                    /*
                     * Successful LE GATT connection.
                     */
                    if (
                        status ==
                        BluetoothGatt.GATT_SUCCESS &&
                        newState ==
                        BluetoothProfile.STATE_CONNECTED
                    ) {

                        Logger.i(
                            "LE GATT connected to $deviceName"
                        )

                        val discoveryStarted =
                            try {

                                gatt.discoverServices()

                            } catch (e: Throwable) {

                                finish(
                                    failure(
                                        """
                                        Connected over LE GATT,
                                        but discoverServices() threw:

                                        ${e.javaClass.name}

                                        ${e.message}
                                        """.trimIndent()
                                    )
                                )

                                false
                            }

                        if (!discoveryStarted) {

                            finish(
                                failure(
                                    """
                                    Connected over LE GATT,
                                    but discoverServices()
                                    returned FALSE.
                                    """.trimIndent()
                                )
                            )
                        }

                        return
                    }

                    /*
                     * Connection attempt failed.
                     */
                    if (
                        status !=
                        BluetoothGatt.GATT_SUCCESS
                    ) {

                        finish(
                            failure(
                                """
                                LE GATT connection failed.

                                Device:
                                $deviceName

                                Address:
                                $maskedAddress

                                GATT status:
                                $status

                                Android never reached
                                service discovery.

                                Your classic HFP/A2DP profiles
                                were not intentionally changed.
                                """.trimIndent()
                            )
                        )

                        return
                    }

                    /*
                     * Disconnected before service discovery finished.
                     */
                    if (
                        newState ==
                        BluetoothProfile.STATE_DISCONNECTED &&
                        !finished.get()
                    ) {

                        finish(
                            failure(
                                """
                                LE GATT disconnected before
                                service discovery completed.

                                Device:
                                $deviceName

                                Address:
                                $maskedAddress
                                """.trimIndent()
                            )
                        )
                    }
                }

                override fun onServicesDiscovered(
                    gatt: BluetoothGatt,
                    status: Int
                ) {

                    Logger.i(
                        "LE GATT services discovered: " +
                            "status=$status"
                    )

                    if (
                        status !=
                        BluetoothGatt.GATT_SUCCESS
                    ) {

                        finish(
                            failure(
                                """
                                LE GATT connected,
                                but service discovery failed.

                                GATT status:
                                $status
                                """.trimIndent()
                            )
                        )

                        return
                    }

                    val serviceUuids =
                        try {

                            gatt.services
                                .map {
                                    it.uuid
                                        .toString()
                                        .lowercase()
                                }
                                .distinct()
                                .sorted()

                        } catch (e: Throwable) {

                            finish(
                                failure(
                                    """
                                    Service discovery completed,
                                    but BTMicFix could not read
                                    the discovered services.

                                    ${e.javaClass.name}

                                    ${e.message}
                                    """.trimIndent()
                                )
                            )

                            return
                        }

                    val hasAscs =
                        serviceUuids.any {
                            it.equals(
                                ASCS_UUID,
                                ignoreCase = true
                            )
                        }

                    val hasPacs =
                        serviceUuids.any {
                            it.equals(
                                PACS_UUID,
                                ignoreCase = true
                            )
                        }

                    val hasBass =
                        serviceUuids.any {
                            it.equals(
                                BASS_UUID,
                                ignoreCase = true
                            )
                        }

                    val cachedAscs =
                        cachedUuids.any {
                            it.equals(
                                ASCS_UUID,
                                ignoreCase = true
                            )
                        }

                    val cachedPacs =
                        cachedUuids.any {
                            it.equals(
                                PACS_UUID,
                                ignoreCase = true
                            )
                        }

                    val cachedText =
                        if (
                            cachedUuids.isEmpty()
                        ) {

                            "NONE"

                        } else {

                            cachedUuids
                                .joinToString("\n")
                        }

                    val liveText =
                        if (
                            serviceUuids.isEmpty()
                        ) {

                            "NONE"

                        } else {

                            serviceUuids
                                .joinToString("\n")
                        }

                    val conclusion =
                        when {

                            hasAscs &&
                                hasPacs ->

                                """
                                RESULT:
                                LE AUDIO SERVICES FOUND.

                                The Buds are exposing both
                                ASCS 0x184E and PACS 0x1850
                                over live LE GATT.

                                That means the earlier cached
                                BluetoothDevice UUID list was
                                incomplete/stale.

                                DO NOT force profiles yet.

                                The next target is refreshing
                                Android's Bluetooth UUID/profile
                                cache so LeAudioService sees
                                the same services.
                                """.trimIndent()

                            hasAscs ->

                                """
                                RESULT:
                                ASCS 0x184E was found,
                                but PACS 0x1850 was not.

                                The Buds are exposing part of
                                the LE Audio service set, but
                                Android is not seeing the full
                                expected LE Audio capability set.
                                """.trimIndent()

                            hasPacs ->

                                """
                                RESULT:
                                PACS 0x1850 was found,
                                but ASCS 0x184E was not.

                                Android can see published audio
                                capabilities, but the Audio Stream
                                Control Service is missing.
                                """.trimIndent()

                            else ->

                                """
                                RESULT:
                                NO CORE LE AUDIO SERVICES FOUND.

                                The live LE GATT scan did not find
                                ASCS 0x184E or PACS 0x1850.

                                This is stronger evidence that the
                                Buds are not exposing their LE Audio
                                unicast services to this Fold6 in
                                the current pairing/mode.
                                """.trimIndent()
                        }

                    finish(
                        ScanResult(
                            success =
                                true,

                            hasAscs =
                                hasAscs,

                            hasPacs =
                                hasPacs,

                            hasBass =
                                hasBass,

                            text =
                                """
                                ===== BTMicFix LIVE LE GATT SCAN =====

                                Device:
                                $deviceName

                                Address:
                                $maskedAddress

                                LE GATT connection:
                                SUCCESS

                                Service discovery:
                                SUCCESS


                                LIVE GATT RESULTS

                                ASCS 0x184E:
                                ${yesNo(hasAscs)}

                                PACS 0x1850:
                                ${yesNo(hasPacs)}

                                BASS 0x184F:
                                ${yesNo(hasBass)}


                                ANDROID CACHED UUID RESULTS

                                Cached ASCS 0x184E:
                                ${yesNo(cachedAscs)}

                                Cached PACS 0x1850:
                                ${yesNo(cachedPacs)}


                                $conclusion


                                LIVE GATT SERVICES:

                                $liveText


                                CACHED BLUETOOTH UUIDS:

                                $cachedText

                                =====================================
                                """.trimIndent()
                        )
                    )
                }
            }

        /*
         * ========================================================
         * OPEN LE GATT CONNECTION
         * ========================================================
         *
         * autoConnect = false
         *
         * TRANSPORT_LE tells Android to use Bluetooth LE rather
         * than BR/EDR for this temporary GATT diagnostic.
         */

        val gatt =
            try {

                device.connectGatt(
                    context.applicationContext,
                    false,
                    callback,
                    BluetoothDevice.TRANSPORT_LE
                )

            } catch (e: Throwable) {

                return failure(
                    """
                    connectGatt(
                        TRANSPORT_LE
                    )

                    threw:

                    ${e.javaClass.name}

                    ${e.message}
                    """.trimIndent()
                )
            }

        if (gatt == null) {

            return failure(
                """
                Android returned NULL from
                connectGatt(TRANSPORT_LE).
                """.trimIndent()
            )
        }

        gattReference.set(
            gatt
        )

        /*
         * ========================================================
         * WAIT FOR RESULT
         * ========================================================
         */

        val completed =
            try {

                latch.await(
                    SCAN_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )

            } catch (_: InterruptedException) {

                false
            }

        val finalResult =
            if (completed) {

                resultReference.get()
                    ?: failure(
                        "GATT scan finished without a result."
                    )

            } else {

                failure(
                    """
                    LIVE LE GATT SCAN TIMED OUT.

                    Device:
                    $deviceName

                    Address:
                    $maskedAddress

                    No Bluetooth profile-policy changes
                    were made.

                    No LE Audio profile connect()
                    command was issued.
                    """.trimIndent()
                )
            }

        /*
         * ========================================================
         * CLEAN UP THE TEMPORARY GATT CLIENT
         * ========================================================
         */

        gattReference
            .get()
            ?.let {

                try {
                    it.close()
                } catch (_: Throwable) {
                }
            }

        Logger.i(
            finalResult.text
        )

        return finalResult
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
        val buds4Matches =
            devices.filter {

                safeDeviceLabel(it)
                    .contains(
                        "Buds4 Pro",
                        ignoreCase = true
                    )
            }

        if (
            buds4Matches.size ==
            1
        ) {

            return buds4Matches.first()
        }

        /*
         * Generic Buds + Pro fallback.
         */
        val budsProMatches =
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
            budsProMatches.size ==
            1
        ) {

            return budsProMatches.first()
        }

        return null
    }

    /*
     * ============================================================
     * SAFE DEVICE NAME
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
     * MASK MAC ADDRESS
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
     * FAILURE RESULT
     * ============================================================
     */

    private fun failure(
        details: String
    ): ScanResult {

        return ScanResult(
            success =
                false,

            hasAscs =
                false,

            hasPacs =
                false,

            hasBass =
                false,

            text =
                """
                ===== BTMicFix LIVE LE GATT SCAN =====
                FAILED

                $details

                =====================================
                """.trimIndent()
        )
    }
}
