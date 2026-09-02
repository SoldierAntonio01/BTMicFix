package com.btmicfix.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
 * Routing priority:
 *
 * 1. Bluetooth LE Audio / BLE Headset
 *    Best available simultaneous playback + microphone route.
 *
 * 2. Bluetooth SCO / HFP
 *    Compatibility fallback. Microphone works, but media quality is lower.
 *
 * Also contains the watchdog that re-applies the communication route when
 * Voice Access or Samsung changes/drops it.
 */
class AudioRoutingManager(
    private val context: Context
) {

    private val audioManager: AudioManager =
        context.getSystemService<AudioManager>()
            ?: throw IllegalStateException("AudioManager not available")

    private val _routingState =
        MutableStateFlow<RoutingState>(RoutingState.Idle)

    val routingState: StateFlow<RoutingState> =
        _routingState.asStateFlow()

    private val _availableDevices =
        MutableStateFlow<List<BluetoothAudioDevice>>(emptyList())

    val availableDevices: StateFlow<List<BluetoothAudioDevice>> =
        _availableDevices.asStateFlow()

    private var currentRoutedDevice: AudioDeviceInfo? = null

    /*
     * ============================================================
     * WATCHDOG
     * ============================================================
     *
     * Checks the route every 250 ms.
     *
     * It does NOT record audio.
     *
     * If Voice Access or Samsung changes the communication device,
     * BTMicFix re-applies the intended Bluetooth route.
     */

    private val watchdogHandler =
        Handler(Looper.getMainLooper())

    private var watchdogRunning = false

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
                        resolveTargetDevice(
                            rememberedTarget
                        )

                    if (target == null) {

                        Logger.w(
                            "Watchdog: Bluetooth target temporarily unavailable"
                        )

                    } else {

                        /*
                         * Android may regenerate AudioDeviceInfo objects.
                         * Keep our stored endpoint fresh.
                         */
                        currentRoutedDevice =
                            target

                        val actual =
                            audioManager.communicationDevice

                        val routeCorrect =
                            actual != null &&
                                samePhysicalDevice(
                                    actual,
                                    target
                                )

                        val modeCorrect =
                            audioManager.mode ==
                                AudioManager.MODE_IN_COMMUNICATION

                        if (!routeCorrect || !modeCorrect) {

                            Logger.w(
                                "Watchdog restoring route. " +
                                    "Actual=${actual?.productName} " +
                                    "[${actual?.let { deviceTypeToString(it.type) }}], " +
                                    "Wanted=${target.productName} " +
                                    "[${deviceTypeToString(target.type)}]"
                            )

                            audioManager.mode =
                                AudioManager.MODE_IN_COMMUNICATION

                            val success =
                                audioManager.setCommunicationDevice(
                                    target
                                )

                            if (success) {

                                val displayName =
                                    buildDisplayName(target)

                                _routingState.value =
                                    RoutingState.Active(
                                        displayName
                                    )

                                Logger.i(
                                    "✓ Watchdog restored " +
                                        deviceTypeToString(target.type)
                                )

                            } else {

                                Logger.w(
                                    "Watchdog retry returned false"
                                )
                            }
                        }
                    }

                } catch (e: Exception) {

                    Logger.e(
                        "Watchdog route check failed",
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
                addedDevices: Array<out AudioDeviceInfo>
            ) {

                Logger.i(
                    "Audio devices added: ${
                        addedDevices.map {
                            deviceTypeToString(it.type)
                        }
                    }"
                )

                refreshAvailableDevices()
            }

            override fun onAudioDevicesRemoved(
                removedDevices: Array<out AudioDeviceInfo>
            ) {

                Logger.i(
                    "Audio devices removed: ${
                        removedDevices.map {
                            deviceTypeToString(it.type)
                        }
                    }"
                )

                val remembered =
                    currentRoutedDevice

                if (remembered != null) {

                    val removed =
                        removedDevices.any {
                            it.id == remembered.id
                        }

                    if (removed) {

                        /*
                         * Samsung can delete/recreate the endpoint while
                         * changing Bluetooth profiles.
                         *
                         * Try to locate the new copy before giving up.
                         */
                        val replacement =
                            resolveTargetDevice(
                                remembered
                            )

                        if (replacement != null) {

                            currentRoutedDevice =
                                replacement

                            Logger.i(
                                "Bluetooth endpoint recreated as " +
                                    deviceTypeToString(
                                        replacement.type
                                    )
                            )

                        } else {

                            Logger.w(
                                "Routed Bluetooth device disappeared"
                            )

                            clearRouting()
                        }
                    }
                }

                refreshAvailableDevices()
            }
        }

    /*
     * ============================================================
     * STATE CLASSES
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
     * MONITORING
     * ============================================================
     */

    fun startMonitoring() {

        Logger.i(
            "Starting audio device monitoring"
        )

        try {

            audioManager.registerAudioDeviceCallback(
                deviceCallback,
                null
            )

        } catch (_: Exception) {

            Logger.w(
                "Audio callback may already be registered"
            )
        }

        refreshAvailableDevices()
    }

    fun stopMonitoring() {

        Logger.i(
            "Stopping audio device monitoring"
        )

        try {

            audioManager.unregisterAudioDeviceCallback(
                deviceCallback
            )

        } catch (_: Exception) {

            // Already removed.
        }
    }

    /*
     * ============================================================
     * MAIN ROUTING
     * ============================================================
     */

    fun routeToBluetooth(
        requestedDevice: AudioDeviceInfo
    ): RoutingState {

        /*
         * If the caller selected SCO but the same Buds have a BLE
         * communication endpoint, upgrade to BLE automatically.
         */
        val device =
            preferBleVersionOfDevice(
                requestedDevice
            )

        val displayName =
            buildDisplayName(device)

        Logger.i(
            "Attempting route to $displayName"
        )

        _routingState.value =
            RoutingState.Routing(
                displayName
            )

        try {

            audioManager.mode =
                AudioManager.MODE_IN_COMMUNICATION

            val success =
                audioManager.setCommunicationDevice(
                    device
                )

            if (success) {

                currentRoutedDevice =
                    device

                val state =
                    RoutingState.Active(
                        displayName
                    )

                _routingState.value =
                    state

                startRouteWatchdog()

                Logger.i(
                    "✓ Routing active: $displayName"
                )

                return state
            }

            /*
             * BLE endpoint was visible, but Samsung rejected it.
             *
             * Fall back to classic HFP/SCO so the Buds microphone
             * still works.
             */
            if (
                device.type ==
                AudioDeviceInfo.TYPE_BLE_HEADSET
            ) {

                Logger.w(
                    "BLE route rejected by Android. Trying HFP/SCO."
                )

                val sco =
                    findScoFallback(
                        device
                    )

                if (sco != null) {

                    audioManager.mode =
                        AudioManager.MODE_IN_COMMUNICATION

                    val scoSuccess =
                        audioManager.setCommunicationDevice(
                            sco
                        )

                    if (scoSuccess) {

                        currentRoutedDevice =
                            sco

                        val scoDisplayName =
                            buildDisplayName(sco)

                        val state =
                            RoutingState.Active(
                                scoDisplayName
                            )

                        _routingState.value =
                            state

                        startRouteWatchdog()

                        Logger.w(
                            "✓ HFP/SCO fallback active: " +
                                scoDisplayName
                        )

                        return state
                    }
                }
            }

            stopRouteWatchdog()

            audioManager.mode =
                AudioManager.MODE_NORMAL

            val state =
                RoutingState.Failed(
                    "Bluetooth communication routing failed"
                )

            _routingState.value =
                state

            Logger.e(
                "✗ Could not establish Bluetooth communication route"
            )

            return state

        } catch (e: Exception) {

            stopRouteWatchdog()

            audioManager.mode =
                AudioManager.MODE_NORMAL

            val state =
                RoutingState.Failed(
                    e.message ?: "Unknown routing error"
                )

            _routingState.value =
                state

            Logger.e(
                "✗ Exception during routing",
                e
            )

            return state
        }
    }

    /*
     * ============================================================
     * AUTOMATIC BEST-QUALITY DEVICE
     * ============================================================
     */

    fun routeToFirstAvailableBluetooth():
        RoutingState {

        val device =
            findFirstBluetoothCommunicationDevice()

        if (device == null) {

            val state =
                RoutingState.Failed(
                    "No Bluetooth communication device found"
                )

            _routingState.value =
                state

            Logger.w(
                "No Bluetooth communication device available"
            )

            return state
        }

        return routeToBluetooth(
            device
        )
    }

    /*
     * ============================================================
     * SAVED DEVICE ADDRESS
     * ============================================================
     */

    fun routeToDeviceByAddress(
        address: String
    ): RoutingState {

        val devices =
            getAvailableCommunicationDevices()

        val exact =
            devices.firstOrNull {
                it.address == address
            }

        if (exact != null) {

            /*
             * routeToBluetooth() will automatically upgrade an SCO
             * endpoint to BLE if a matching BLE endpoint exists.
             */
            return routeToBluetooth(
                exact
            )
        }

        Logger.w(
            "Saved Bluetooth address ${
                if (BuildConfig.DEBUG) {
                    address
                } else {
                    "REDACTED"
                }
            } not exposed as communication endpoint. " +
                "Trying best available connected headset."
        )

        return routeToFirstAvailableBluetooth()
    }

    /*
     * ============================================================
     * CLEAR ROUTING
     * ============================================================
     */

    fun clearRouting() {

        Logger.i(
            "Clearing Bluetooth communication routing"
        )

        stopRouteWatchdog()

        try {

            audioManager.clearCommunicationDevice()

            audioManager.mode =
                AudioManager.MODE_NORMAL

            currentRoutedDevice =
                null

            _routingState.value =
                RoutingState.Idle

            Logger.i(
                "Routing cleared — normal media audio restored"
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
     * AVAILABLE COMMUNICATION DEVICES
     * ============================================================
     */

    fun getAvailableCommunicationDevices():
        List<AudioDeviceInfo> {

        return audioManager.availableCommunicationDevices
    }

    /*
     * ============================================================
     * BEST QUALITY SELECTION
     * ============================================================
     *
     * THIS IS THE IMPORTANT PART:
     *
     * BLE HEADSET FIRST
     *
     * HFP/SCO SECOND
     */

    fun findFirstBluetoothCommunicationDevice():
        AudioDeviceInfo? {

        val devices =
            audioManager.availableCommunicationDevices

        /*
         * #1 — BEST CHOICE
         *
         * Bluetooth LE Audio / LC3 communication endpoint.
         */
        val ble =
            devices.firstOrNull {
                it.type ==
                    AudioDeviceInfo.TYPE_BLE_HEADSET
            }

        if (ble != null) {

            Logger.i(
                "✓ BLE/LE Audio headset available: " +
                    "${ble.productName}"
            )

            return ble
        }

        /*
         * #2 — FALLBACK
         *
         * Classic Bluetooth Hands-Free Profile.
         */
        val sco =
            devices.firstOrNull {
                it.type ==
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }

        if (sco != null) {

            Logger.w(
                "BLE/LE Audio endpoint NOT available. " +
                    "Falling back to HFP/SCO: " +
                    "${sco.productName}"
            )

            return sco
        }

        Logger.w(
            "No BLE Headset or HFP/SCO device available"
        )

        return null
    }

    /*
     * ============================================================
     * REAL ROUTE CHECK
     * ============================================================
     */

    fun isBluetoothRouted():
        Boolean {

        val intended =
            currentRoutedDevice
                ?: return false

        val actual =
            audioManager.communicationDevice
                ?: return false

        return (
            _routingState.value
                is RoutingState.Active
            ) &&
            samePhysicalDevice(
                actual,
                intended
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

        watchdogHandler.removeCallbacks(
            watchdogRunnable
        )

        watchdogHandler.post(
            watchdogRunnable
        )

        Logger.i(
            "Route watchdog started"
        )
    }

    private fun stopRouteWatchdog() {

        watchdogRunning =
            false

        watchdogHandler.removeCallbacks(
            watchdogRunnable
        )

        Logger.i(
            "Route watchdog stopped"
        )
    }

    /*
     * ============================================================
     * FIND CURRENT COPY OF TARGET DEVICE
     * ============================================================
     */

    private fun resolveTargetDevice(
        remembered: AudioDeviceInfo
    ): AudioDeviceInfo? {

        val devices =
            audioManager
                .availableCommunicationDevices
                .filter {
                    isBluetoothCommunicationDevice(
                        it
                    )
                }

        /*
         * Stay on the same Bluetooth transport whenever possible.
         *
         * If we successfully started on BLE, don't silently accept
         * HFP just because Samsung regenerated the endpoint.
         */
        val sameType =
            devices.filter {
                it.type ==
                    remembered.type
            }

        /*
         * Same internal Android ID.
         */
        sameType.firstOrNull {
            it.id ==
                remembered.id
        }?.let {
            return it
        }

        /*
         * Same Bluetooth address.
         */
        if (
            remembered.address.isNotBlank()
        ) {

            sameType.firstOrNull {
                it.address ==
                    remembered.address
            }?.let {
                return it
            }
        }

        /*
         * Same product name.
         */
        val name =
            remembered.productName
                ?.toString()

        if (!name.isNullOrBlank()) {

            sameType.firstOrNull {
                it.productName
                    ?.toString() ==
                    name
            }?.let {
                return it
            }
        }

        /*
         * Samsung regenerated the endpoint and there is only one
         * endpoint of the requested transport.
         */
        if (sameType.size == 1) {

            return sameType.first()
        }

        /*
         * If we WERE using BLE but Samsung completely removed the
         * BLE communication endpoint, preserve microphone function
         * by falling back to SCO.
         */
        if (
            remembered.type ==
            AudioDeviceInfo.TYPE_BLE_HEADSET
        ) {

            val sco =
                findScoFallback(
                    remembered
                )

            if (sco != null) {

                Logger.w(
                    "BLE endpoint disappeared — " +
                        "using HFP/SCO fallback"
                )

                return sco
            }
        }

        /*
         * Last resort:
         * one BT communication endpoint remains.
         */
        return if (devices.size == 1) {

            devices.first()

        } else {

            null
        }
    }

    /*
     * ============================================================
     * ROUTE COMPARISON
     * ============================================================
     */

    private fun samePhysicalDevice(
        first: AudioDeviceInfo,
        second: AudioDeviceInfo
    ): Boolean {

        /*
         * IMPORTANT:
         *
         * BLE and SCO are intentionally considered DIFFERENT.
         *
         * Otherwise the watchdog could think everything was fine
         * after Samsung silently changed:
         *
         * BLE -> HFP/SCO
         */
        if (
            first.type !=
            second.type
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
            first.address.isNotBlank() &&
            second.address.isNotBlank() &&
            first.address ==
                second.address
        ) {
            return true
        }

        val firstName =
            first.productName
                ?.toString()

        val secondName =
            second.productName
                ?.toString()

        return (
            !firstName.isNullOrBlank() &&
            firstName ==
                secondName &&
            isBluetoothCommunicationDevice(
                first
            ) &&
            isBluetoothCommunicationDevice(
                second
            )
        )
    }

    /*
     * ============================================================
     * UPGRADE SCO REQUEST TO BLE
     * ============================================================
     */

    private fun preferBleVersionOfDevice(
        original: AudioDeviceInfo
    ): AudioDeviceInfo {

        /*
         * Already BLE.
         */
        if (
            original.type ==
            AudioDeviceInfo.TYPE_BLE_HEADSET
        ) {
            return original
        }

        val devices =
            audioManager.availableCommunicationDevices

        val originalName =
            original.productName
                ?.toString()

        /*
         * Try to find a BLE endpoint with the same name.
         */
        if (!originalName.isNullOrBlank()) {

            devices.firstOrNull {

                it.type ==
                    AudioDeviceInfo.TYPE_BLE_HEADSET &&

                    it.productName
                        ?.toString() ==
                    originalName

            }?.let {

                Logger.i(
                    "✓ Upgrading classic Bluetooth request " +
                        "to matching BLE/LE Audio endpoint"
                )

                return it
            }
        }

        /*
         * If exactly one BLE communication headset exists,
         * it is almost certainly the connected Buds.
         */
        val bleDevices =
            devices.filter {

                it.type ==
                    AudioDeviceInfo.TYPE_BLE_HEADSET
            }

        if (bleDevices.size == 1) {

            Logger.i(
                "✓ Using sole BLE/LE Audio headset instead of HFP"
            )

            return bleDevices.first()
        }

        return original
    }

    /*
     * ============================================================
     * FIND HFP/SCO FALLBACK
     * ============================================================
     */

    private fun findScoFallback(
        source: AudioDeviceInfo
    ): AudioDeviceInfo? {

        val devices =
            audioManager.availableCommunicationDevices

        val sourceName =
            source.productName
                ?.toString()

        /*
         * Prefer matching product name.
         */
        if (!sourceName.isNullOrBlank()) {

            devices.firstOrNull {

                it.type ==
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&

                    it.productName
                        ?.toString() ==
                    sourceName

            }?.let {

                return it
            }
        }

        /*
         * Otherwise use the only SCO headset.
         */
        val scoDevices =
            devices.filter {

                it.type ==
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }

        return if (scoDevices.size == 1) {

            scoDevices.first()

        } else {

            null
        }
    }

    /*
     * ============================================================
     * BLUETOOTH COMMUNICATION DEVICE CHECK
     * ============================================================
     */

    private fun isBluetoothCommunicationDevice(
        device: AudioDeviceInfo
    ): Boolean {

        return (
            device.type ==
                AudioDeviceInfo.TYPE_BLE_HEADSET ||

            device.type ==
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        )
    }

    /*
     * ============================================================
     * UI / DEBUG DEVICE LIST
     * ============================================================
     */

    private fun refreshAvailableDevices() {

        val communicationDevices =
            audioManager.availableCommunicationDevices

        val bluetoothDevices =
            communicationDevices
                .filter {

                    it.type ==
                        AudioDeviceInfo.TYPE_BLE_HEADSET ||

                    it.type ==
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||

                    it.type ==
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                }
                .sortedBy {

                    /*
                     * BLE first.
                     */
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
            bluetoothDevices

        Logger.d(
            "Available Bluetooth devices: ${
                bluetoothDevices.map {
                    "${it.name} [${it.typeLabel}]"
                }
            }"
        )
    }

    /*
     * ============================================================
     * DISPLAY NAME
     * ============================================================
     */

    private fun buildDisplayName(
        device: AudioDeviceInfo
    ): String {

        val name =
            device.productName
                ?.toString()
                ?: "Bluetooth Device"

        return "$name • ${deviceTypeToString(device.type)}"
    }

    /*
     * ============================================================
     * CONSTANTS
     * ============================================================
     */

    companion object {

        /*
         * Your working Voice Access recovery interval.
         */
        private const val WATCHDOG_INTERVAL_MS =
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
