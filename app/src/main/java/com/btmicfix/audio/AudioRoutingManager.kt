package com.btmicfix.audio

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.getSystemService
import com.btmicfix.BuildConfig
import com.btmicfix.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BTMicFix Audio Routing Manager
 *
 * DIAGNOSTIC / STRICT LE AUDIO VERSION
 *
 * Goal:
 *
 * 1. Check whether the phone supports LE Audio.
 * 2. Check whether the Buds are connected to Android's LE Audio profile.
 * 3. Check whether Android exposes TYPE_BLE_HEADSET as a communication route.
 * 4. Route ONLY through BLE Headset / LE Audio.
 * 5. DO NOT silently fall back to HFP/SCO.
 * 6. Keep the working 250 ms Voice Access watchdog.
 *
 * This lets us determine exactly where Samsung is forcing HFP.
 */
class AudioRoutingManager(
    private val context: Context
) {

    private val audioManager: AudioManager =
        context.getSystemService<AudioManager>()
            ?: throw IllegalStateException(
                "AudioManager not available"
            )

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService<BluetoothManager>()

    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val _routingState =
        MutableStateFlow<RoutingState>(
            RoutingState.Idle
        )

    val routingState: StateFlow<RoutingState> =
        _routingState.asStateFlow()

    private val _availableDevices =
        MutableStateFlow<
            List<BluetoothAudioDevice>
        >(emptyList())

    val availableDevices:
        StateFlow<List<BluetoothAudioDevice>> =
        _availableDevices.asStateFlow()

    private var currentRoutedDevice:
        AudioDeviceInfo? = null

    /*
     * ============================================================
     * LE AUDIO PROFILE INFORMATION
     * ============================================================
     */

    private var leAudioProfileConnected =
        false

    private var leAudioConnectedDeviceNames:
        List<String> = emptyList()

    private var leAudioProfileProxy:
        BluetoothProfile? = null

    private val leAudioProfileListener =
        object : BluetoothProfile.ServiceListener {

            override fun onServiceConnected(
                profile: Int,
                proxy: BluetoothProfile
            ) {

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.TIRAMISU &&
                    profile ==
                    BluetoothProfile.LE_AUDIO
                ) {

                    leAudioProfileProxy =
                        proxy

                    try {

                        val connected =
                            proxy.connectedDevices

                        leAudioProfileConnected =
                            connected.isNotEmpty()

                        leAudioConnectedDeviceNames =
                            connected.map { device ->

                                try {
                                    device.name
                                        ?: device.address
                                } catch (_: SecurityException) {
                                    "LE Audio Device"
                                }
                            }

                        Logger.i(
                            "LE Audio profile connected = " +
                                leAudioProfileConnected
                        )

                        Logger.i(
                            "LE Audio profile devices = " +
                                leAudioConnectedDeviceNames
                        )

                    } catch (e: SecurityException) {

                        leAudioProfileConnected =
                            false

                        leAudioConnectedDeviceNames =
                            emptyList()

                        Logger.e(
                            "Unable to read LE Audio profile devices",
                            e
                        )
                    }

                    refreshAvailableDevices()
                    logAudioDiagnostics()
                }
            }

            override fun onServiceDisconnected(
                profile: Int
            ) {

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.TIRAMISU &&
                    profile ==
                    BluetoothProfile.LE_AUDIO
                ) {

                    leAudioProfileConnected =
                        false

                    leAudioConnectedDeviceNames =
                        emptyList()

                    leAudioProfileProxy =
                        null

                    Logger.w(
                        "LE Audio profile disconnected"
                    )

                    logAudioDiagnostics()
                }
            }
        }

    /*
     * ============================================================
     * WATCHDOG
     * ============================================================
     */

    private val watchdogHandler =
        Handler(
            Looper.getMainLooper()
        )

    private var watchdogRunning =
        false

    private val watchdogRunnable =
        object : Runnable {

            override fun run() {

                if (!watchdogRunning) {
                    return
                }

                val rememberedTarget =
                    currentRoutedDevice

                if (rememberedTarget == null) {

                    stopRouteWatchdog()
                    return
                }

                try {

                    val target =
                        resolveBleTargetDevice(
                            rememberedTarget
                        )

                    val actual =
                        audioManager.communicationDevice

                    if (target != null) {

                        currentRoutedDevice =
                            target

                        val routeCorrect =
                            actual != null &&
                                sameBleDevice(
                                    actual,
                                    target
                                )

                        val modeCorrect =
                            audioManager.mode ==
                                AudioManager
                                    .MODE_IN_COMMUNICATION

                        if (
                            !routeCorrect ||
                            !modeCorrect
                        ) {

                            Logger.w(
                                "Watchdog: BLE route dropped. " +
                                    "Actual=${actual?.productName} " +
                                    "[${actual?.let {
                                        deviceTypeToString(
                                            it.type
                                        )
                                    }}], " +
                                    "Wanted=${target.productName} " +
                                    "[BLE Headset / LE Audio]"
                            )

                            audioManager.mode =
                                AudioManager
                                    .MODE_IN_COMMUNICATION

                            val success =
                                audioManager
                                    .setCommunicationDevice(
                                        target
                                    )

                            if (success) {

                                val displayName =
                                    buildDisplayName(
                                        target
                                    )

                                _routingState.value =
                                    RoutingState.Active(
                                        displayName
                                    )

                                Logger.i(
                                    "✓ Watchdog restored BLE/LE Audio route"
                                )

                            } else {

                                Logger.w(
                                    "Watchdog: Android rejected BLE route retry"
                                )
                            }
                        }

                    } else {

                        /*
                         * IMPORTANT:
                         *
                         * We intentionally DO NOT grab HFP here.
                         *
                         * If Android removes TYPE_BLE_HEADSET,
                         * keep checking for BLE instead.
                         */

                        Logger.w(
                            "Watchdog: TYPE_BLE_HEADSET currently unavailable"
                        )
                    }

                } catch (e: Exception) {

                    Logger.e(
                        "BLE watchdog failed",
                        e
                    )
                }

                if (watchdogRunning) {

                    watchdogHandler.postDelayed(
                        this,
                        WATCHDOG_INTERVAL_MS
                    )
                }
            }
        }

    /*
     * ============================================================
     * AUDIO DEVICE CALLBACK
     * ============================================================
     */

    private val deviceCallback =
        object : AudioDeviceCallback() {

            override fun onAudioDevicesAdded(
                addedDevices:
                    Array<out AudioDeviceInfo>
            ) {

                Logger.i(
                    "Audio devices added: ${
                        addedDevices.map {
                            deviceTypeToString(
                                it.type
                            )
                        }
                    }"
                )

                refreshAvailableDevices()
                logAudioDiagnostics()
            }

            override fun onAudioDevicesRemoved(
                removedDevices:
                    Array<out AudioDeviceInfo>
            ) {

                Logger.i(
                    "Audio devices removed: ${
                        removedDevices.map {
                            deviceTypeToString(
                                it.type
                            )
                        }
                    }"
                )

                val remembered =
                    currentRoutedDevice

                if (remembered != null) {

                    val targetWasRemoved =
                        removedDevices.any {
                            it.id ==
                                remembered.id
                        }

                    if (targetWasRemoved) {

                        val replacement =
                            resolveBleTargetDevice(
                                remembered
                            )

                        if (replacement != null) {

                            currentRoutedDevice =
                                replacement

                            Logger.i(
                                "BLE endpoint recreated by Android"
                            )

                        } else {

                            /*
                             * Don't switch to HFP.
                             *
                             * The watchdog will wait for the
                             * BLE endpoint to come back.
                             */

                            Logger.w(
                                "BLE endpoint disappeared. " +
                                    "NOT falling back to HFP."
                            )
                        }
                    }
                }

                refreshAvailableDevices()
                logAudioDiagnostics()
            }
        }

    /*
     * ============================================================
     * STATE
     * ============================================================
     */

    sealed class RoutingState {

        data object Idle :
            RoutingState()

        data class Routing(
            val deviceName: String
        ) : RoutingState()

        data class Active(
            val deviceName: String
        ) : RoutingState()

        data class Failed(
            val reason: String
        ) : RoutingState()
    }

    data class BluetoothAudioDevice(

        val deviceInfo:
            AudioDeviceInfo,

        val name:
            String,

        val type:
            Int,

        val typeLabel:
            String
    )

    /*
     * ============================================================
     * START MONITORING
     * ============================================================
     */

    fun startMonitoring() {

        Logger.i(
            "Starting audio monitoring"
        )

        try {

            audioManager
                .registerAudioDeviceCallback(
                    deviceCallback,
                    null
                )

        } catch (_: Exception) {

            Logger.w(
                "Audio callback may already be registered"
            )
        }

        startLeAudioProfileCheck()

        refreshAvailableDevices()

        logAudioDiagnostics()
    }

    /*
     * ============================================================
     * STOP MONITORING
     * ============================================================
     */

    fun stopMonitoring() {

        Logger.i(
            "Stopping audio monitoring"
        )

        try {

            audioManager
                .unregisterAudioDeviceCallback(
                    deviceCallback
                )

        } catch (_: Exception) {
        }

        stopLeAudioProfileCheck()
    }

    /*
     * ============================================================
     * START LE AUDIO PROFILE CHECK
     * ============================================================
     */

    private fun startLeAudioProfileCheck() {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.TIRAMISU
        ) {

            Logger.w(
                "Android version is below Android 13. " +
                    "LE Audio API unavailable."
            )

            return
        }

        val adapter =
            bluetoothAdapter
                ?: return

        try {

            val requested =
                adapter.getProfileProxy(
                    context,
                    leAudioProfileListener,
                    BluetoothProfile.LE_AUDIO
                )

            Logger.i(
                "Requested LE Audio profile proxy = $requested"
            )

        } catch (e: SecurityException) {

            Logger.e(
                "Missing Bluetooth permission for LE profile",
                e
            )

        } catch (e: Exception) {

            Logger.e(
                "Could not request LE Audio profile",
                e
            )
        }
    }

    /*
     * ============================================================
     * STOP LE AUDIO PROFILE CHECK
     * ============================================================
     */

    private fun stopLeAudioProfileCheck() {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }

        val adapter =
            bluetoothAdapter
                ?: return

        val proxy =
            leAudioProfileProxy
                ?: return

        try {

            adapter.closeProfileProxy(
                BluetoothProfile.LE_AUDIO,
                proxy
            )

        } catch (_: Exception) {
        }

        leAudioProfileProxy =
            null
    }

    /*
     * ============================================================
     * ROUTE TO BLUETOOTH
     * ============================================================
     *
     * STRICT BLE MODE:
     *
     * Even if the requested device is HFP,
     * search for a BLE version first.
     *
     * If BLE doesn't exist:
     *
     * FAIL.
     *
     * DO NOT FALL BACK TO HFP.
     */

    fun routeToBluetooth(
        requestedDevice:
            AudioDeviceInfo
    ): RoutingState {

        val bleDevice =
            findMatchingBleDevice(
                requestedDevice
            )

        if (bleDevice == null) {

            val diagnostic =
                getAudioDiagnosticText()

            val state =
                RoutingState.Failed(
                    "NO BLE HEADSET ROUTE\n\n" +
                        diagnostic
                )

            _routingState.value =
                state

            Logger.e(
                "STRICT BLE: No TYPE_BLE_HEADSET available"
            )

            Logger.e(
                diagnostic
            )

            return state
        }

        val displayName =
            buildDisplayName(
                bleDevice
            )

        _routingState.value =
            RoutingState.Routing(
                displayName
            )

        Logger.i(
            "Attempting STRICT BLE route: " +
                displayName
        )

        try {

            audioManager.mode =
                AudioManager
                    .MODE_IN_COMMUNICATION

            val success =
                audioManager
                    .setCommunicationDevice(
                        bleDevice
                    )

            if (success) {

                currentRoutedDevice =
                    bleDevice

                val state =
                    RoutingState.Active(
                        displayName
                    )

                _routingState.value =
                    state

                startRouteWatchdog()

                Logger.i(
                    "✓ BLE/LE AUDIO ROUTING ACTIVE"
                )

                logAudioDiagnostics()

                return state

            } else {

                stopRouteWatchdog()

                audioManager.mode =
                    AudioManager.MODE_NORMAL

                val diagnostic =
                    getAudioDiagnosticText()

                val state =
                    RoutingState.Failed(
                        "Android exposed BLE Headset " +
                            "but rejected setCommunicationDevice().\n\n" +
                            diagnostic
                    )

                _routingState.value =
                    state

                Logger.e(
                    "Android rejected TYPE_BLE_HEADSET routing"
                )

                return state
            }

        } catch (e: Exception) {

            stopRouteWatchdog()

            audioManager.mode =
                AudioManager.MODE_NORMAL

            val state =
                RoutingState.Failed(
                    "BLE routing exception: " +
                        (
                            e.message
                                ?: "Unknown error"
                            )
                )

            _routingState.value =
                state

            Logger.e(
                "BLE routing exception",
                e
            )

            return state
        }
    }

    /*
     * ============================================================
     * ROUTE TO FIRST AVAILABLE BLUETOOTH
     * ============================================================
     */

    fun routeToFirstAvailableBluetooth():
        RoutingState {

        val bleDevice =
            findFirstBluetoothCommunicationDevice()

        if (bleDevice == null) {

            val diagnostic =
                getAudioDiagnosticText()

            val state =
                RoutingState.Failed(
                    "BLE HEADSET NOT AVAILABLE\n\n" +
                        diagnostic
                )

            _routingState.value =
                state

            Logger.e(
                diagnostic
            )

            return state
        }

        return routeToBluetooth(
            bleDevice
        )
    }

    /*
     * ============================================================
     * ROUTE BY SAVED ADDRESS
     * ============================================================
     */

    fun routeToDeviceByAddress(
        address: String
    ): RoutingState {

        val available =
            audioManager
                .availableCommunicationDevices

        val savedDevice =
            available.firstOrNull {
                it.address ==
                    address
            }

        /*
         * Even if savedDevice is an HFP endpoint,
         * routeToBluetooth() searches for its
         * BLE equivalent.
         */

        if (savedDevice != null) {

            return routeToBluetooth(
                savedDevice
            )
        }

        Logger.w(
            "Saved address ${
                if (BuildConfig.DEBUG) {
                    address
                } else {
                    "REDACTED"
                }
            } not directly available."
        )

        /*
         * Try any BLE headset.
         */

        return routeToFirstAvailableBluetooth()
    }

    /*
     * ============================================================
     * FIND FIRST BLUETOOTH COMMUNICATION DEVICE
     * ============================================================
     *
     * STRICT:
     *
     * Only TYPE_BLE_HEADSET qualifies.
     */

    fun findFirstBluetoothCommunicationDevice():
        AudioDeviceInfo? {

        val devices =
            audioManager
                .availableCommunicationDevices

        val ble =
            devices.firstOrNull {
                it.type ==
                    AudioDeviceInfo
                        .TYPE_BLE_HEADSET
            }

        if (ble != null) {

            Logger.i(
                "✓ TYPE_BLE_HEADSET FOUND: " +
                    "${ble.productName}"
            )

            return ble
        }

        val hfp =
            devices.firstOrNull {
                it.type ==
                    AudioDeviceInfo
                        .TYPE_BLUETOOTH_SCO
            }

        if (hfp != null) {

            Logger.w(
                "HFP exists but BLE does NOT. " +
                    "HFP=${hfp.productName}"
            )
        }

        return null
    }

    /*
     * ============================================================
     * FIND MATCHING BLE DEVICE
     * ============================================================
     */

    private fun findMatchingBleDevice(
        original:
            AudioDeviceInfo
    ): AudioDeviceInfo? {

        val devices =
            audioManager
                .availableCommunicationDevices

        /*
         * Already BLE.
         */

        if (
            original.type ==
            AudioDeviceInfo.TYPE_BLE_HEADSET
        ) {

            return original
        }

        val originalName =
            original
                .productName
                ?.toString()

        /*
         * Same product name.
         */

        if (
            !originalName
                .isNullOrBlank()
        ) {

            devices.firstOrNull {

                it.type ==
                    AudioDeviceInfo.TYPE_BLE_HEADSET &&

                    it.productName
                        ?.toString() ==
                    originalName

            }?.let {

                Logger.i(
                    "Found BLE equivalent for " +
                        originalName
                )

                return it
            }
        }

        /*
         * If there is exactly one BLE headset,
         * use it.
         */

        val bleDevices =
            devices.filter {
                it.type ==
                    AudioDeviceInfo
                        .TYPE_BLE_HEADSET
            }

        if (
            bleDevices.size == 1
        ) {

            Logger.i(
                "Using only available BLE headset"
            )

            return bleDevices.first()
        }

        return null
    }

    /*
     * ============================================================
     * RESOLVE BLE TARGET
     * ============================================================
     */

    private fun resolveBleTargetDevice(
        remembered:
            AudioDeviceInfo
    ): AudioDeviceInfo? {

        val bleDevices =
            audioManager
                .availableCommunicationDevices
                .filter {
                    it.type ==
                        AudioDeviceInfo
                            .TYPE_BLE_HEADSET
                }

        /*
         * Same Android ID.
         */

        bleDevices.firstOrNull {
            it.id ==
                remembered.id
        }?.let {

            return it
        }

        /*
         * Same Bluetooth address.
         */

        if (
            remembered.address
                .isNotBlank()
        ) {

            bleDevices.firstOrNull {
                it.address ==
                    remembered.address
            }?.let {

                return it
            }
        }

        /*
         * Same device name.
         */

        val rememberedName =
            remembered
                .productName
                ?.toString()

        if (
            !rememberedName
                .isNullOrBlank()
        ) {

            bleDevices.firstOrNull {
                it.productName
                    ?.toString() ==
                    rememberedName
            }?.let {

                return it
            }
        }

        /*
         * Only one BLE device.
         */

        return if (
            bleDevices.size == 1
        ) {

            bleDevices.first()

        } else {

            null
        }
    }

    /*
     * ============================================================
     * REAL ROUTING CHECK
     * ============================================================
     */

    fun isBluetoothRouted():
        Boolean {

        val target =
            currentRoutedDevice
                ?: return false

        val actual =
            audioManager
                .communicationDevice
                ?: return false

        return (
            _routingState.value
                is RoutingState.Active
            ) &&
            sameBleDevice(
                actual,
                target
            )
    }

    /*
     * ============================================================
     * COMPARE BLE DEVICES
     * ============================================================
     */

    private fun sameBleDevice(
        first:
            AudioDeviceInfo,
        second:
            AudioDeviceInfo
    ): Boolean {

        /*
         * Both MUST actually be BLE.
         */

        if (
            first.type !=
            AudioDeviceInfo.TYPE_BLE_HEADSET
        ) {

            return false
        }

        if (
            second.type !=
            AudioDeviceInfo.TYPE_BLE_HEADSET
        ) {

            return false
        }

        if (
            first.id ==
            second.id
        ) {

            return true
        }

        if (
            first.address
                .isNotBlank() &&
            second.address
                .isNotBlank() &&
            first.address ==
                second.address
        ) {

            return true
        }

        val firstName =
            first
                .productName
                ?.toString()

        val secondName =
            second
                .productName
                ?.toString()

        return (
            !firstName
                .isNullOrBlank() &&
            firstName ==
                secondName
        )
    }

    /*
     * ============================================================
     * CLEAR ROUTING
     * ============================================================
     */

    fun clearRouting() {

        Logger.i(
            "Clearing BLE communication route"
        )

        stopRouteWatchdog()

        try {

            audioManager
                .clearCommunicationDevice()

            audioManager.mode =
                AudioManager.MODE_NORMAL

            currentRoutedDevice =
                null

            _routingState.value =
                RoutingState.Idle

            Logger.i(
                "Routing cleared. SSC/UHQ media can resume."
            )

        } catch (e: Exception) {

            Logger.e(
                "Error clearing routing",
                e
            )
        }
    }

    /*
     * ============================================================
     * AVAILABLE DEVICES
     * ============================================================
     */

    fun getAvailableCommunicationDevices():
        List<AudioDeviceInfo> {

        return audioManager
            .availableCommunicationDevices
    }

    /*
     * ============================================================
     * REFRESH DEVICE LIST
     * ============================================================
     */

    private fun refreshAvailableDevices() {

        val devices =
            audioManager
                .availableCommunicationDevices

        val btDevices =
            devices
                .filter {

                    it.type ==
                        AudioDeviceInfo.TYPE_BLE_HEADSET ||

                    it.type ==
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||

                    it.type ==
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                }
                .sortedBy {

                    when (it.type) {

                        AudioDeviceInfo.TYPE_BLE_HEADSET ->
                            0

                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
                            1

                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ->
                            2

                        else ->
                            3
                    }
                }
                .map {

                    BluetoothAudioDevice(

                        deviceInfo =
                            it,

                        name =
                            it.productName
                                ?.toString()
                                ?: "Unknown Bluetooth Device",

                        type =
                            it.type,

                        typeLabel =
                            deviceTypeToString(
                                it.type
                            )
                    )
                }

        _availableDevices.value =
            btDevices

        Logger.i(
            "Communication devices = ${
                btDevices.map {
                    "${it.name} [${it.typeLabel}]"
                }
            }"
        )
    }

    /*
     * ============================================================
     * LE AUDIO HARDWARE SUPPORT
     * ============================================================
     */

    private fun getLeAudioHardwareSupport():
        String {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.TIRAMISU
        ) {

            return "API unavailable"
        }

        val adapter =
            bluetoothAdapter
                ?: return "NO Bluetooth adapter"

        return try {

            when (
                adapter.isLeAudioSupported
            ) {

                BluetoothStatusCodes.FEATURE_SUPPORTED ->
                    "YES"

                BluetoothStatusCodes.FEATURE_NOT_SUPPORTED ->
                    "NO"

                else ->
                    "UNKNOWN"
            }

        } catch (e: SecurityException) {

            "PERMISSION ERROR"

        } catch (_: Exception) {

            "UNKNOWN"
        }
    }

    /*
     * ============================================================
     * FULL DIAGNOSTIC TEXT
     * ============================================================
     */

    fun getAudioDiagnosticText():
        String {

        val devices =
            audioManager
                .availableCommunicationDevices

        val bleRoute =
            devices.firstOrNull {
                it.type ==
                    AudioDeviceInfo
                        .TYPE_BLE_HEADSET
            }

        val hfpRoute =
            devices.firstOrNull {
                it.type ==
                    AudioDeviceInfo
                        .TYPE_BLUETOOTH_SCO
            }

        val current =
            audioManager
                .communicationDevice

        val currentText =
            if (current == null) {

                "NONE"

            } else {

                "${current.productName} " +
                    "[${deviceTypeToString(current.type)}]"
            }

        val leProfileNames =
            if (
                leAudioConnectedDeviceNames
                    .isEmpty()
            ) {

                "NONE"

            } else {

                leAudioConnectedDeviceNames
                    .joinToString(", ")
            }

        return buildString {

            appendLine(
                "===== BTMicFix LE AUDIO TEST ====="
            )

            appendLine(
                "Phone LE Audio support: " +
                    getLeAudioHardwareSupport()
            )

            appendLine(
                "LE Audio profile connected: " +
                    if (leAudioProfileConnected) {
                        "YES"
                    } else {
                        "NO"
                    }
            )

            appendLine(
                "LE profile devices: " +
                    leProfileNames
            )

            appendLine(
                "BLE Headset route available: " +
                    if (bleRoute != null) {
                        "YES"
                    } else {
                        "NO"
                    }
            )

            appendLine(
                "HFP/SCO route available: " +
                    if (hfpRoute != null) {
                        "YES"
                    } else {
                        "NO"
                    }
            )

            appendLine(
                "Current communication route: " +
                    currentText
            )

            if (bleRoute != null) {

                appendLine(
                    "BLE route name: " +
                        "${bleRoute.productName}"
                )

                appendLine(
                    "BLE route address: " +
                        if (BuildConfig.DEBUG) {
                            bleRoute.address
                        } else {
                            "REDACTED"
                        }
                )
            }

            if (hfpRoute != null) {

                appendLine(
                    "HFP route name: " +
                        "${hfpRoute.productName}"
                )
            }

            appendLine(
                "================================="
            )
        }
    }

    /*
     * ============================================================
     * LOG DIAGNOSTICS
     * ============================================================
     */

    private fun logAudioDiagnostics() {

        Logger.i(
            getAudioDiagnosticText()
        )
    }

    /*
     * ============================================================
     * WATCHDOG CONTROL
     * ============================================================
     */

    private fun startRouteWatchdog() {

        if (watchdogRunning) {
            return
        }

        watchdogRunning =
            true

        watchdogHandler
            .removeCallbacks(
                watchdogRunnable
            )

        watchdogHandler
            .post(
                watchdogRunnable
            )

        Logger.i(
            "BLE route watchdog started"
        )
    }

    private fun stopRouteWatchdog() {

        watchdogRunning =
            false

        watchdogHandler
            .removeCallbacks(
                watchdogRunnable
            )

        Logger.i(
            "BLE route watchdog stopped"
        )
    }

    /*
     * ============================================================
     * DISPLAY NAME
     * ============================================================
     */

    private fun buildDisplayName(
        device:
            AudioDeviceInfo
    ): String {

        val name =
            device
                .productName
                ?.toString()
                ?: "Bluetooth Device"

        return "$name • BLE Headset / LE Audio"
    }

    /*
     * ============================================================
     * CONSTANTS
     * ============================================================
     */

    companion object {

        /*
         * Keep your proven Voice Access watchdog speed.
         */

        private const val
            WATCHDOG_INTERVAL_MS =
            250L

        fun deviceTypeToString(
            type: Int
        ): String {

            return when (type) {

                AudioDeviceInfo.TYPE_BLE_HEADSET ->
                    "BLE Headset / LE Audio"

                AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
                    "HFP / SCO"

                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ->
                    "A2DP Media"

                AudioDeviceInfo.TYPE_BLE_SPEAKER ->
                    "BLE Speaker"

                AudioDeviceInfo.TYPE_BUILTIN_MIC ->
                    "Built-in Mic"

                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ->
                    "Built-in Speaker"

                AudioDeviceInfo.TYPE_WIRED_HEADSET ->
                    "Wired Headset"

                AudioDeviceInfo.TYPE_USB_DEVICE ->
                    "USB Device"

                AudioDeviceInfo.TYPE_USB_HEADSET ->
                    "USB Headset"

                else ->
                    "Type $type"
            }
        }
    }
}
