package com.btmicfix.shizuku

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.AttributionSource
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
 * BTMicFix - Safer LE Audio connection experiment.
 *
 * IMPORTANT DIFFERENCE FROM PREVIOUS VERSION:
 *
 * We DO NOT call:
 *
 * setConnectionPolicy(ALLOWED)
 *
 * anymore.
 *
 * Your Buds already reported:
 *
 * Policy before = ALLOWED (100)
 * Policy after  = ALLOWED (100)
 *
 * Re-setting the policy was unnecessary and could cause
 * Android/Samsung to begin an LE Audio handover while the
 * actual LE connection still failed.
 *
 *
 * NEW FLOW:
 *
 * 1. Find the real paired Galaxy Buds device.
 * 2. Read its cached Bluetooth service UUIDs.
 * 3. Look specifically for LE Audio UUID 0x184E.
 *
 *      If 184E is missing:
 *          STOP.
 *          Do NOT change profiles.
 *          Do NOT disconnect HFP/A2DP.
 *
 *      If 184E exists:
 *          Continue.
 *
 * 4. Obtain Android's BluetoothLeAudio profile proxy.
 * 5. Check current LE connection state.
 * 6. Explicitly call LE Audio connect().
 * 7. Wait for actual STATE_CONNECTED.
 * 8. Confirm TYPE_BLE_HEADSET appears.
 *
 * No silent HFP fallback is performed here.
 */
object LeAudioShizukuBridge {

    /*
     * ============================================================
     * CONSTANTS
     * ============================================================
     */

    private const val SHELL_UID =
        2000

    private const val SHELL_PACKAGE =
        "com.android.shell"

    /*
     * Android BluetoothUuid.LE_AUDIO
     *
     * Audio Stream Control Service
     * Bluetooth Assigned Number 0x184E
     */
    private const val LE_AUDIO_UUID =
        "0000184e-0000-1000-8000-00805f9b34fb"

    /*
     * Wait for BluetoothLeAudio profile proxy.
     */
    private const val PROFILE_PROXY_TIMEOUT_SECONDS =
        12L

    /*
     * After connect() is accepted, poll for the actual LE connection.
     */
    private const val CONNECTION_TIMEOUT_MS =
        15000L

    private const val CONNECTION_POLL_MS =
        500L

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
                title =
                    "FAILED",

                details =
                    """
                    Android 13 or newer is required
                    for Bluetooth LE Audio APIs.
                    """.trimIndent()
            )
        }

        /*
         * --------------------------------------------------------
         * BLUETOOTH_CONNECT PERMISSION
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
                title =
                    "FAILED",

                details =
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
                    title =
                        "FAILED",

                    details =
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

                return buildResult(
                    title =
                        "FAILED",

                    details =
                        """
                        BTMicFix does not have
                        Shizuku permission.
                        """.trimIndent()
                )
            }

        } catch (e: Throwable) {

            return buildResult(
                title =
                    "FAILED",

                details =
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
         * HiddenApiBypass does NOT grant system permission.
         *
         * It only allows BTMicFix to access Android's hidden
         * Bluetooth framework interfaces.
         */

        try {

            HiddenApiBypass
                .setHiddenApiExemptions(
                    "Landroid/bluetooth/",
                    "Lcom/android/modules/utils/"
                )

        } catch (e: Throwable) {

            Logger.w(
                "Hidden API exemption setup failed: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
        }

        /*
         * --------------------------------------------------------
         * NORMAL APP BLUETOOTH MANAGER
         * --------------------------------------------------------
         *
         * We intentionally do this from the normal BTMicFix process.
         *
         * BluetoothAdapter did NOT work from the Shizuku UserService.
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
                title =
                    "FAILED",

                details =
                    """
                    BluetoothManager could not be obtained
                    from the normal BTMicFix process.
                    """.trimIndent()
            )
        }

        val adapter =
            try {

                bluetoothManager.adapter

            } catch (e: Throwable) {

                return buildResult(
                    title =
                        "FAILED",

                    details =
                        """
                        BluetoothManager exists,
                        but BluetoothAdapter access failed.

                        ${e.javaClass.name}

                        ${e.message}
                        """.trimIndent()
                )
            }

        if (adapter == null) {

            return buildResult(
                title =
                    "FAILED",

                details =
                    """
                    BluetoothAdapter unexpectedly
                    returned NULL.
                    """.trimIndent()
            )
        }

        val bluetoothEnabled =
            try {

                adapter.isEnabled

            } catch (_: Throwable) {

                false
            }

        if (!bluetoothEnabled) {

            return buildResult(
                title =
                    "FAILED",

                details =
                    "Bluetooth is OFF."
            )
        }

        /*
         * ========================================================
         * FIND THE REAL PAIRED BUDS
         * ========================================================
         */

        val pairedDevices =
            try {

                adapter
                    .bondedDevices
                    .toList()

            } catch (e: Throwable) {

                return buildResult(
                    title =
                        "FAILED",

                    details =
                        """
                        Could not read paired Bluetooth devices.

                        ${e.javaClass.name}

                        ${e.message}
                        """.trimIndent()
                )
            }

        if (pairedDevices.isEmpty()) {

            return buildResult(
                title =
                    "FAILED",

                details =
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
                pairedDevices
                    .joinToString(
                        separator = "\n"
                    ) {

                        safeDeviceLabel(it)
                    }

            return buildResult(
                title =
                    "FAILED",

                details =
                    """
                    Could not identify the selected Buds.

                    Selected:
                    $preferredDeviceName

                    Paired devices:

                    $pairedNames
                    """.trimIndent()
            )
        }

        val maskedAddress =
            maskAddress(
                try {
                    device.address
                } catch (_: Throwable) {
                    ""
                }
            )

        val deviceLabel =
            safeDeviceLabel(
                device
            )

        /*
         * ========================================================
         * READ REMOTE BLUETOOTH UUIDS
         * ========================================================
         *
         * THIS IS IMPORTANT.
         *
         * Android's own LeAudioService.connect() checks whether
         * the remote device has BluetoothUuid.LE_AUDIO.
         *
         * That UUID is 0x184E.
         *
         * If it is missing, Android will refuse the LE connection.
         *
         * So we test BEFORE touching the connection.
         */

        val remoteUuids =
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

        val hasLeAudioUuid =
            remoteUuids.any {
                it.equals(
                    LE_AUDIO_UUID,
                    ignoreCase = true
                )
            }

        val uuidDisplay =
            if (
                remoteUuids.isEmpty()
            ) {

                "NONE / NOT CACHED"

            } else {

                remoteUuids
                    .joinToString(
                        separator = "\n"
                    )
            }

        Logger.i(
            "BTMicFix paired Buds: $deviceLabel " +
                "address=$maskedAddress " +
                "has184E=$hasLeAudioUuid"
        )

        /*
         * ========================================================
         * SAFETY STOP
         * ========================================================
         *
         * DO NOT try LE Audio if Android itself does not report
         * the LE Audio service UUID for this paired identity.
         *
         * This is deliberately different from the previous build.
         */

        if (!hasLeAudioUuid) {

            return buildResult(
                title =
                    "STOPPED SAFELY",

                details =
                    """
                    No Bluetooth profile changes were made.

                    Device:
                    $deviceLabel

                    Address:
                    $maskedAddress

                    Android does NOT currently report
                    the LE Audio service UUID 0x184E
                    for this paired Buds identity.

                    LE Audio UUID needed:
                    $LE_AUDIO_UUID

                    Cached Buds UUIDs:

                    $uuidDisplay

                    Android's LE Audio service normally
                    refuses connect() when 0x184E is missing.

                    Your HFP/A2DP connection was left alone.
                    """.trimIndent()
            )
        }

        /*
         * ========================================================
         * REQUEST BLUETOOTH LE AUDIO PROFILE PROXY
         * ========================================================
         */

        val callbackResult =
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

                        callbackResult.set(
                            buildResult(
                                title =
                                    "FAILED",

                                details =
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

                        callbackResult.set(
                            connectLeAudioSafely(
                                context =
                                    context,

                                proxy =
                                    proxy,

                                device =
                                    device,

                                deviceLabel =
                                    deviceLabel,

                                maskedAddress =
                                    maskedAddress,

                                uuidDisplay =
                                    uuidDisplay
                            )
                        )

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

                        callbackResult.set(
                            buildResult(
                                title =
                                    "FAILED",

                                details =
                                    """
                                    LE Audio connection test threw:

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
                        callbackResult.get() ==
                        null
                    ) {

                        callbackResult.set(
                            buildResult(
                                title =
                                    "FAILED",

                                details =
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
         * START PROFILE PROXY REQUEST
         * --------------------------------------------------------
         */

        val profileRequestStarted =
            try {

                adapter.getProfileProxy(
                    context.applicationContext,
                    listener,
                    BluetoothProfile.LE_AUDIO
                )

            } catch (e: Throwable) {

                return buildResult(
                    title =
                        "FAILED",

                    details =
                        """
                        Could not request Android's
                        Bluetooth LE Audio profile.

                        ${e.javaClass.name}

                        ${e.message}
                        """.trimIndent()
                )
            }

        if (!profileRequestStarted) {

            return buildResult(
                title =
                    "FAILED",

                details =
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
                    PROFILE_PROXY_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )

            } catch (_: InterruptedException) {

                false
            }

        val finalResult =
            if (callbackReceived) {

                callbackResult.get()
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
                        Android did not deliver the
                        LE Audio profile callback within
                        $PROFILE_PROXY_TIMEOUT_SECONDS seconds.

                        No connection-policy change was made.
                        """.trimIndent()
                )
            }

        /*
         * --------------------------------------------------------
         * RELEASE LOCAL PROXY
         * --------------------------------------------------------
         *
         * This does NOT disconnect LE Audio.
         *
         * It only releases BTMicFix's Java profile proxy.
         */

        proxyReference
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
            "BTMicFix LE Audio result:\n$finalResult"
        )

        return finalResult
    }

    /*
     * ============================================================
     * ACTUAL LE AUDIO CONNECTION TEST
     * ============================================================
     */

    private fun connectLeAudioSafely(
        context: Context,
        proxy: BluetoothProfile,
        device: BluetoothDevice,
        deviceLabel: String,
        maskedAddress: String,
        uuidDisplay: String
    ): String {

        /*
         * --------------------------------------------------------
         * CURRENT STATE
         * --------------------------------------------------------
         */

        val stateBefore =
            try {

                proxy.getConnectionState(
                    device
                )

            } catch (_: Throwable) {

                -1
            }

        /*
         * If Android already connected LE Audio,
         * don't issue another connect request.
         */

        if (
            stateBefore ==
            BluetoothProfile.STATE_CONNECTED
        ) {

            val bleRouteVisible =
                isBleCommunicationRouteVisible(
                    context
                )

            return buildResult(
                title =
                    "ACCEPTED - ALREADY CONNECTED",

                details =
                    """
                    LE Audio is already connected.

                    Device:
                    $deviceLabel

                    Address:
                    $maskedAddress

                    LE state:
                    CONNECTED

                    BLE Headset communication route:
                    ${yesNo(bleRouteVisible)}

                    BTMicFix can now attempt the
                    BLE communication route.
                    """.trimIndent()
            )
        }

        /*
         * --------------------------------------------------------
         * READ CURRENT POLICY
         * --------------------------------------------------------
         *
         * Diagnostic only.
         *
         * WE DO NOT CHANGE IT.
         */

        val policy =
            readLePolicyThroughShizuku(
                proxy =
                    proxy,

                device =
                    device
            )

        /*
         * --------------------------------------------------------
         * CONNECT()
         * --------------------------------------------------------
         *
         * First try Android's hidden BluetoothLeAudio.connect(device)
         * directly from the normal BTMicFix process.
         *
         * AOSP defines connect() as requiring BLUETOOTH_CONNECT.
         *
         * If Samsung blocks that hidden framework call,
         * we fall back to the Shizuku-wrapped LE Audio Binder.
         */

        var connectMethod =
            "BluetoothLeAudio.connect(device)"

        var connectAccepted =
            tryNormalHiddenConnect(
                proxy =
                    proxy,

                device =
                    device
            )

        /*
         * null = hidden framework invocation itself failed.
         *
         * In that situation, use the Shizuku Binder path.
         */

        if (
            connectAccepted ==
            null
        ) {

            connectMethod =
                "Shizuku-wrapped IBluetoothLeAudio.connect()"

            connectAccepted =
                callConnectThroughShizuku(
                    proxy =
                        proxy,

                    device =
                        device
                )
        }

        /*
         * --------------------------------------------------------
         * CONNECT IMMEDIATELY REJECTED
         * --------------------------------------------------------
         */

        if (
            connectAccepted !=
            true
        ) {

            val stateAfterReject =
                try {

                    proxy.getConnectionState(
                        device
                    )

                } catch (_: Throwable) {

                    -1
                }

            return buildResult(
                title =
                    "REJECTED",

                details =
                    """
                    Android rejected the direct
                    LE Audio connect request.

                    Device:
                    $deviceLabel

                    Address:
                    $maskedAddress

                    LE UUID 0x184E:
                    YES

                    LE policy:
                    $policy

                    Connect path:
                    $connectMethod

                    LE state before:
                    ${connectionStateName(stateBefore)}

                    LE state after:
                    ${connectionStateName(stateAfterReject)}

                    No connection policy was changed.

                    Cached UUIDs:

                    $uuidDisplay
                    """.trimIndent()
            )
        }

        /*
         * --------------------------------------------------------
         * CONNECT REQUEST ACCEPTED
         * --------------------------------------------------------
         *
         * TRUE only means Android accepted the asynchronous request.
         *
         * Now wait for actual STATE_CONNECTED.
         */

        val startTime =
            System.currentTimeMillis()

        var finalState =
            stateBefore

        while (
            System.currentTimeMillis() -
            startTime <
            CONNECTION_TIMEOUT_MS
        ) {

            finalState =
                try {

                    proxy.getConnectionState(
                        device
                    )

                } catch (_: Throwable) {

                    -1
                }

            if (
                finalState ==
                BluetoothProfile.STATE_CONNECTED
            ) {

                break
            }

            try {

                Thread.sleep(
                    CONNECTION_POLL_MS
                )

            } catch (_: InterruptedException) {

                break
            }
        }

        /*
         * --------------------------------------------------------
         * REAL SUCCESS
         * --------------------------------------------------------
         */

        if (
            finalState ==
            BluetoothProfile.STATE_CONNECTED
        ) {

            val bleRouteVisible =
                isBleCommunicationRouteVisible(
                    context
                )

            return buildResult(
                title =
                    "ACCEPTED - LE AUDIO CONNECTED",

                details =
                    """
                    SUCCESS.

                    Android actually connected
                    the Buds through LE Audio.

                    Device:
                    $deviceLabel

                    Address:
                    $maskedAddress

                    LE UUID 0x184E:
                    YES

                    LE policy:
                    $policy

                    Connect path:
                    $connectMethod

                    LE state before:
                    ${connectionStateName(stateBefore)}

                    LE state now:
                    CONNECTED

                    BLE Headset communication route:
                    ${yesNo(bleRouteVisible)}

                    BTMicFix can now try
                    TYPE_BLE_HEADSET.
                    """.trimIndent()
            )
        }

        /*
         * --------------------------------------------------------
         * CONNECT REQUEST STARTED BUT NEVER FINISHED
         * --------------------------------------------------------
         *
         * IMPORTANT:
         *
         * Do NOT include the word "ACCEPTED" in the title.
         *
         * Your HomeScreen only automatically attempts
         * TYPE_BLE_HEADSET when the result contains ACCEPTED.
         *
         * We don't want it routing until LE is truly connected.
         */

        return buildResult(
            title =
                "LE CONNECT DID NOT COMPLETE",

            details =
                """
                Android accepted the connect request,
                but LE Audio did NOT reach CONNECTED
                within ${CONNECTION_TIMEOUT_MS / 1000} seconds.

                Device:
                $deviceLabel

                Address:
                $maskedAddress

                LE UUID 0x184E:
                YES

                LE policy:
                $policy

                Connect path:
                $connectMethod

                LE state before:
                ${connectionStateName(stateBefore)}

                Final LE state:
                ${connectionStateName(finalState)}

                BTMicFix did NOT change the
                LE connection policy.

                It will NOT automatically force
                TYPE_BLE_HEADSET after this result.

                If Samsung temporarily disconnected
                the classic Buds connection during
                the handover attempt, reconnect the
                Buds once from Bluetooth settings.
                """.trimIndent()
        )
    }

    /*
     * ============================================================
     * NORMAL HIDDEN BluetoothLeAudio.connect()
     * ============================================================
     *
     * Returns:
     *
     * true  = Android accepted request
     * false = Android rejected request
     * null  = hidden invocation itself unavailable/failed
     */

    private fun tryNormalHiddenConnect(
        proxy: BluetoothProfile,
        device: BluetoothDevice
    ): Boolean? {

        return try {

            val result =
                HiddenApiBypass.invoke(
                    proxy.javaClass,
                    proxy,
                    "connect",
                    device
                )

            val booleanResult =
                result as? Boolean

            Logger.i(
                "Normal hidden BluetoothLeAudio.connect() = " +
                    booleanResult
            )

            booleanResult

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

            Logger.w(
                "Normal hidden connect unavailable: " +
                    "${actual.javaClass.simpleName}: " +
                    "${actual.message}"
            )

            null
        }
    }

    /*
     * ============================================================
     * SHIZUKU-WRAPPED LE AUDIO connect()
     * ============================================================
     */

    private fun callConnectThroughShizuku(
        proxy: BluetoothProfile,
        device: BluetoothDevice
    ): Boolean {

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
         * The Binder transaction is forwarded through Shizuku.
         */

        val privilegedBinder =
            ShizukuBinderWrapper(
                rawBinder
            )

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
         * On your current Samsung build, setConnectionPolicy()
         * exposed the direct Binder form:
         *
         * setConnectionPolicy(
         *     BluetoothDevice,
         *     int,
         *     AttributionSource
         * )
         *
         * The matching direct LE connect form is normally:
         *
         * connect(
         *     BluetoothDevice,
         *     AttributionSource
         * )
         */

        val connectMethods =
            privilegedService
                .javaClass
                .methods
                .filter {
                    it.name ==
                        "connect"
                }

        val directMethod =
            connectMethods
                .firstOrNull { method ->

                    val types =
                        method.parameterTypes

                    types.size == 2 &&

                        types[0] ==
                        BluetoothDevice::class.java &&

                        types[1].name ==
                        "android.content.AttributionSource"
                }

        if (
            directMethod !=
            null
        ) {

            directMethod.isAccessible =
                true

            val result =
                directMethod.invoke(
                    privilegedService,
                    device,
                    shellSource
                )

            return result as? Boolean
                ?: false
        }

        /*
         * If Samsung changes the hidden AIDL signature,
         * don't guess.
         *
         * Print exactly what is exposed.
         */

        val signatures =
            if (
                connectMethods.isEmpty()
            ) {

                "NONE"

            } else {

                connectMethods
                    .joinToString(
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
            Supported IBluetoothLeAudio.connect()
            signature not found.

            Connect methods exposed by Samsung:

            $signatures
            """.trimIndent()
        )
    }

    /*
     * ============================================================
     * READ LE AUDIO POLICY
     * ============================================================
     *
     * Diagnostic only.
     *
     * THIS FUNCTION NEVER CHANGES THE POLICY.
     */

    private fun readLePolicyThroughShizuku(
        proxy: BluetoothProfile,
        device: BluetoothDevice
    ): String {

        return try {

            val rawService =
                HiddenApiBypass.invoke(
                    proxy.javaClass,
                    proxy,
                    "getService"
                )
                    ?: return "UNAVAILABLE"

            val rawInterface =
                rawService as? IInterface
                    ?: return "UNAVAILABLE"

            val privilegedBinder =
                ShizukuBinderWrapper(
                    rawInterface.asBinder()
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
                    ?: return "UNAVAILABLE"

            val shellSource =
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
                            "getConnectionPolicy"
                    }

            /*
             * Modern/direct Samsung form.
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
                if (
                    modern != null
                ) {

                    modern.isAccessible =
                        true

                    modern.invoke(
                        service,
                        device,
                        shellSource
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

                    if (
                        legacy != null
                    ) {

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
     * CHECK WHETHER ANDROID CREATED TYPE_BLE_HEADSET
     * ============================================================
     */

    private fun isBleCommunicationRouteVisible(
        context: Context
    ): Boolean {

        val audioManager =
            try {

                context.getSystemService(
                    AudioManager::class.java
                )

            } catch (_: Throwable) {

                null
            }
                ?: return false

        return try {

            audioManager
                .availableCommunicationDevices
                .any {

                    it.type ==
                        AudioDeviceInfo.TYPE_BLE_HEADSET
                }

        } catch (_: Throwable) {

            false
        }
    }

    /*
     * ============================================================
     * FIND THE CORRECT PAIRED BUDS
     * ============================================================
     */

    private fun findBestMatchingDevice(
        pairedDevices: List<BluetoothDevice>,
        preferredName: String
    ): BluetoothDevice? {

        val wanted =
            preferredName.trim()

        /*
         * #1 Exact alias match.
         */

        pairedDevices
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
         * #2 Exact Bluetooth device-name match.
         */

        pairedDevices
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
         * #3 Galaxy Buds4 Pro match.
         */

        val buds4Matches =
            pairedDevices
                .filter { device ->

                    val text =
                        safeDeviceLabel(
                            device
                        )

                    text.contains(
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
         * #4 Generic Buds Pro match.
         */

        val genericMatches =
            pairedDevices
                .filter { device ->

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
            genericMatches.size ==
            1
        ) {

            return genericMatches.first()
        }

        return null
    }

    /*
     * ============================================================
     * SAFE DEVICE LABEL
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
     * MASK REAL BLUETOOTH ADDRESS
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

        val finalFive =
            address.takeLast(
                5
            )

        return "XX:XX:XX:XX:$finalFive"
    }

    /*
     * ============================================================
     * STATE NAME
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
     * RESULT FORMAT
     * ============================================================
     */

    private fun buildResult(
        title: String,
        details: String
    ): String {

        return """
            ===== BTMicFix SAFE LE TEST =====
            $title

            $details
            =================================
        """.trimIndent()
    }
}
