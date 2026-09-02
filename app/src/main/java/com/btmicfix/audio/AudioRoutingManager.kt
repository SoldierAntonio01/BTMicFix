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
 * Core Bluetooth audio routing manager.
 *
 * PRIORITY:
 *
 * 1. BLE Headset / LE Audio
 *    - Best simultaneous playback + microphone quality
 *
 * 2. Bluetooth SCO / HFP
 *    - Fallback when LE Audio communication routing is unavailable
 *
 * Includes a 250 ms watchdog that restores the Bluetooth communication
 * route whenever Voice Access, Samsung, or another app drops/replaces it.
 */
class AudioRoutingManager(
    private val context: Context
) {

    private val audioManager: AudioManager =
        context.getSystemService<AudioManager>()
            ?: throw IllegalStateException(
                "AudioManager not available"
            )

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
     * ---------------------------------------------------------
     * WATCHDOG
     * ---------------------------------------------------------
     *
     * Does NOT record audio.
     *
     * It simply checks whether Android is still using the
     * Bluetooth communication device that BTMicFix requested.
     *
     * Voice Access / Samsung can sometimes knock the route off.
     * When that happens, this restores it.
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
                        resolveTargetDevice(
                            rememberedTarget
                        )

                    val actual =
                        audioManager.communicationDevice

                    if (target != null) {

                        /*
                         * Android may regenerate the internal
                         * AudioDeviceInfo object.
                         *
                         * Keep our remembered target fresh.
                         */
                        currentRoutedDevice =
                            target

                        val routeIsCorrect =
                            actual != null &&
                                samePhysicalDevice(
                                    actual,
                                    target
                                )

                        val modeIsCorrect =
                            audioManager.mode ==
                                AudioManager
                                    .MODE_IN_COMMUNICATION

                        if (
                            !routeIsCorrect ||
                            !modeIsCorrect
                        ) {

                            Logger.w(
                                "Watchdog: route dropped. " +
                                    "Actual=${actual?.productName} " +
                                    "(${actual?.let { deviceTypeToString(it.type) }}) " +
                                    "Target=${target.productName} " +
                                    "(${deviceTypeToString(target.type)}). " +
                                    "Re-applying."
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

                                val deviceName =
                                    target
                                        .productName
                                        ?.toString()
                                        ?: "Bluetooth Device"

                                _routingState.value =
                                    RoutingState.Active(
                                        deviceName
                                    )

                                Logger.i(
                                    "✓ Watchdog restored " +
                                        "${deviceTypeToString(target.type)} route"
                                )

                            } else {

                                Logger.w(
                                    "Watchdog retry failed. " +
                                        "Will retry."
                                )
                            }
                        }

                    } else {

                        Logger.w(
                            "Watchdog: target Bluetooth " +
                                "communication endpoint unavailable"
                        )
                    }

                } catch (e: Exception) {

                    Logger.e(
                        "Watchdog route check failed",
                        e
                    )
                }

                if (watchdogRunning) {

                    watchdogHandler
                        .postDelayed(
                            this,
                            WATCHDOG_INTERVAL_MS
                        )
                }
            }
        }

    /*
     * ---------------------------------------------------------
     * AUDIO DEVICE CALLBACK
     * ---------------------------------------------------------
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

                currentRoutedDevice
                    ?.let { routed ->

                        val routedWasRemoved =
                            removedDevices.any {
                                it.id == routed.id
                            }

                        if (routedWasRemoved) {

                            val replacement =
                                resolveTargetDevice(
                                    routed
                                )

                            if (
                                replacement == null
                            ) {

                                Logger.w(
                                    "Routed Bluetooth " +
                                        "device disappeared"
                                )

                                clearRouting()

                            } else {

                                /*
                                 * Samsung recreated the endpoint.
                                 * Update the watchdog target instead
                                 * of killing routing.
                                 */
                                currentRoutedDevice =
                                    replacement

                                Logger.i(
                                    "Bluetooth endpoint recreated. " +
                                        "New target = " +
                                        deviceTypeToString(
                                            replacement.type
                                        )
                                )
                            }
                        }
                    }

                refreshAvailableDevices()
            }
        }

    /*
     * ---------------------------------------------------------
     * ROUTING STATE
     * ---------------------------------------------------------
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
     * ---------------------------------------------------------
     * START / STOP MONITORING
     * ---------------------------------------------------------
     */

    fun startMonitoring() {

        Logger.i(
            "Starting audio device monitoring"
        )

        try {

            audioManager
                .registerAudioDeviceCallback(
                    deviceCallback,
                    null
                )

        } catch (e: Exception) {

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

            audioManager
                .unregisterAudioDeviceCallback(
                    deviceCallback
                )

        } catch (_: Exception) {

            // Already unregistered.
        }
    }

    /*
     * ---------------------------------------------------------
     * ROUTE TO SPECIFIC DEVICE
     * ---------------------------------------------------------
     */

    fun routeToBluetooth(
        device: AudioDeviceInfo
    ): RoutingState {

        /*
         * If somebody hands us a SCO endpoint,
         * see whether an LE Audio version of the
         * same headset is available first.
         */

        val preferredDevice =
            preferBleVersionOfDevice(
                device
            )

        val deviceName =
            preferredDevice
                .productName
                ?.toString()
                ?: "Bluetooth Device"

        val typeName =
            deviceTypeToString(
                preferredDevice.type
            )

        Logger.i(
            "Attempting Bluetooth routing: " +
                "$deviceName [$typeName]"
        )

        _routingState.value =
            RoutingState.Routing(
                deviceName
            )

        try {

            audioManager.mode =
                AudioManager
                    .MODE_IN_COMMUNICATION

            val success =
                audioManager
                    .setCommunicationDevice(
                        preferredDevice
                    )

            if (success) {

                currentRoutedDevice =
                    preferredDevice

                val state =
                    RoutingState.Active(
                        deviceName
                    )

                _routingState.value =
                    state

                startRouteWatchdog()

                Logger.i(
                    "✓ Routing active: " +
                        "$deviceName [$typeName] " +
                        "(watchdog enabled)"
                )

                return state

            } else {

                /*
                 * If LE Audio was selected but Samsung
                 * rejected it, try classic SCO once.
                 */

                if (
                    preferredDevice.type ==
                    AudioDeviceInfo.TYPE_BLE_HEADSET
                ) {

                    Logger.w(
                        "LE Audio routing was rejected. " +
                            "Trying classic SCO fallback."
                    )

                    val scoFallback =
                        findScoFallback(
                            preferredDevice
                        )

                    if (scoFallback != null) {

                        val scoSuccess =
                            audioManager
                                .setCommunicationDevice(
                                    scoFallback
                                )

                        if (scoSuccess) {

                            currentRoutedDevice =
                                scoFallback

                            val scoName =
                                scoFallback
                                    .productName
                                    ?.toString()
                                    ?: deviceName

                            val state =
                                RoutingState.Active(
                                    scoName
                                )

                            _routingState.value =
                                state

                            startRouteWatchdog()

                            Logger.w(
                                "✓ SCO fallback active: " +
                                    "$scoName"
                            )

                            return state
                        }
                    }
                }

                stopRouteWatchdog()

                val state =
                    RoutingState.Failed(
                        "setCommunicationDevice returned false"
                    )

                _routingState.value =
                    state

                Logger.e(
                    "✗ Bluetooth routing failed"
                )

                audioManager.mode =
                    AudioManager.MODE_NORMAL

                return state
            }

        } catch (e: Exception) {

            stopRouteWatchdog()

            val state =
                RoutingState.Failed(
                    e.message
                        ?: "Unknown routing error"
                )

            _routingState.value =
                state

            Logger.e(
                "✗ Exception during routing",
                e
            )

            audioManager.mode =
                AudioManager.MODE_NORMAL

            return state
        }
    }

    /*
     * ---------------------------------------------------------
     * BEST QUALITY AUTOMATIC DEVICE SELECTION
     * ---------------------------------------------------------
     */

    fun routeToFirstAvailableBluetooth():
        RoutingState {

        val btDevice =
            findFirstBluetoothCommunicationDevice()

        if (btDevice == null) {

            val state =
                RoutingState.Failed(
                    "No Bluetooth communication device found"
                )

            _routingState.value =
                state

            Logger.w(
                "No Bluetooth communication endpoint available"
            )

            return state
        }

        return routeToBluetooth(
            btDevice
        )
    }

    /*
     * ---------------------------------------------------------
     * ROUTE BY SAVED ADDRESS
     * ---------------------------------------------------------
     */

    fun routeToDeviceByAddress(
        address: String
    ): RoutingState {

        val devices =
            getAvailableCommunicationDevices()

        val savedDevice =
            devices.find {
                it.address == address
            }

        /*
         * The companion-device address may point to
         * the classic Bluetooth endpoint.
         *
         * Prefer an LE Audio endpoint belonging to
         * the same Buds whenever possible.
         */

        if (savedDevice != null) {

            return routeToBluetooth(
                savedDevice
            )
        }

        /*
         * Samsung can expose communication endpoints
         * with a different/private address.
         *
         * If the saved address no longer appears,
         * use our preferred connected BT device.
         */

        Logger.w(
            "Saved address ${
                if (BuildConfig.DEBUG)
                    address
                else
                    "REDACTED"
            } not directly available. " +
                "Trying preferred connected Bluetooth headset."
        )

        return routeToFirstAvailableBluetooth()
    }

    /*
     * ---------------------------------------------------------
     * CLEAR ROUTING
     * ---------------------------------------------------------
     */

    fun clearRouting() {

        Logger.i(
            "Clearing Bluetooth audio routing"
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
                "Routing cleared. " +
                    "Returning to normal high-quality media audio."
            )

        } catch (e: Exception) {

            Logger.e(
                "Error clearing routing",
                e
            )
        }
    }

    /*
     * ---------------------------------------------------------
     * AVAILABLE DEVICES
     * ---------------------------------------------------------
     */

    fun getAvailableCommunicationDevices():
        List<AudioDeviceInfo> {

        return audioManager
            .availableCommunicationDevices
    }

    /*
     * ---------------------------------------------------------
     * MOST IMPORTANT QUALITY SELECTION
     * ---------------------------------------------------------
     *
     * LE AUDIO FIRST.
     *
     * Only use SCO if LE Audio is unavailable.
     */

    fun findFirstBluetoothCommunicationDevice():
        AudioDeviceInfo? {

        val devices =
            audioManager
                .availableCommunicationDevices

        /*
         * BEST:
         * Bluetooth LE Audio / LC3
         */

        devices
            .firstOrNull {
                it.type ==
                    AudioDeviceInfo
                        .TYPE_BLE_HEADSET
            }
            ?.let { bleDevice ->

                Logger.i(
                    "✓ Preferred LE Audio endpoint found: " +
                        "${bleDevice.productName}"
                )

                return bleDevice
            }

        /*
         * FALLBACK:
         * Classic Bluetooth SCO / HFP
         */

        return devices
            .firstOrNull {
                it.type ==
                    AudioDeviceInfo
                        .TYPE_BLUETOOTH_SCO
            }
            ?.also { scoDevice ->

                Logger.w(
                    "LE Audio unavailable. " +
                        "Using SCO fallback: " +
                        "${scoDevice.productName}"
                )
            }
    }

    /*
     * ---------------------------------------------------------
     * REAL ROUTING CHECK
     * ---------------------------------------------------------
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
            samePhysicalDevice(
                actual,
                target
            )
    }

    /*
     * ---------------------------------------------------------
     * WATCHDOG START / STOP
     * ---------------------------------------------------------
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
            "Route watchdog started"
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
            "Route watchdog stopped"
        )
    }

    /*
     * ---------------------------------------------------------
     * FIND UPDATED VERSION OF CURRENT DEVICE
     * ---------------------------------------------------------
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
         * Try to stay on the SAME TYPE.
         *
         * If we started on BLE, do not casually
         * switch to SCO just because Android
         * regenerated the endpoint.
         */

        val sameTypeDevices =
            devices.filter {
                it.type ==
                    remembered.type
            }

        sameTypeDevices
            .firstOrNull {
                it.id ==
                    remembered.id
            }
            ?.let {
                return it
            }

        if (
            remembered.address
                .isNotBlank()
        ) {

            sameTypeDevices
                .firstOrNull {
                    it.address ==
                        remembered.address
                }
                ?.let {
                    return it
                }
        }

        val rememberedName =
            remembered
                .productName
                ?.toString()

        if (
            !rememberedName
                .isNullOrBlank()
        ) {

            sameTypeDevices
                .firstOrNull {
                    it.productName
                        ?.toString() ==
                        rememberedName
                }
                ?.let {
                    return it
                }
        }

        if (
            sameTypeDevices.size ==
            1
        ) {

            return sameTypeDevices
                .first()
        }

        /*
         * If LE disappeared completely,
         * preserve microphone functionality by
         * falling back to SCO.
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
                    "LE Audio endpoint disappeared. " +
                        "Falling back to SCO to keep mic alive."
                )

                return sco
            }
        }

        /*
         * Last resort:
         * if exactly one BT communication endpoint
         * remains, it is almost certainly our Buds.
         */

        return if (
            devices.size == 1
        ) {

            devices.first()

        } else {

            null
        }
    }

    /*
     * ---------------------------------------------------------
     * DEVICE COMPARISON
     * ---------------------------------------------------------
     */

    private fun samePhysicalDevice(
        first: AudioDeviceInfo,
        second: AudioDeviceInfo
    ): Boolean {

        /*
         * IMPORTANT:
         *
         * BLE Headset and classic SCO are not the
         * same ROUTE from a quality standpoint.
         *
         * This lets the watchdog notice when
         * Samsung silently changes BLE -> SCO.
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
     * ---------------------------------------------------------
     * IF GIVEN SCO, SEE IF THE SAME BUDS HAVE BLE
     * ---------------------------------------------------------
     */

    private fun preferBleVersionOfDevice(
        original:
            AudioDeviceInfo
    ): AudioDeviceInfo {

        if (
            original.type ==
            AudioDeviceInfo.TYPE_BLE_HEADSET
        ) {

            return original
        }

        val devices =
            audioManager
                .availableCommunicationDevices

        val originalName =
            original
                .productName
                ?.toString()

        /*
         * First try:
         * same product name + BLE endpoint.
         */

        if (
            !originalName
                .isNullOrBlank()
        ) {

            devices
                .firstOrNull {
                    it.type ==
                        AudioDeviceInfo.TYPE_BLE_HEADSET &&
                    it.productName
                        ?.toString() ==
                        originalName
                }
                ?.let {

                    Logger.i(
                        "Upgrading requested SCO route " +
                            "to matching BLE Headset route"
                    )

                    return it
                }
        }

        /*
         * If only one BLE headset is connected,
         * that is almost certainly the Buds.
         */

        val bleDevices =
            devices.filter {
                it.type ==
                    AudioDeviceInfo.TYPE_BLE_HEADSET
            }

        if (
            bleDevices.size == 1
        ) {

            Logger.i(
                "Using sole available BLE Headset " +
                    "instead of classic SCO"
            )

            return bleDevices.first()
        }

        return original
    }

    /*
     * ---------------------------------------------------------
     * SCO FALLBACK
     * ---------------------------------------------------------
     */

    private fun findScoFallback(
        device:
            AudioDeviceInfo
    ): AudioDeviceInfo? {

        val devices =
            audioManager
                .availableCommunicationDevices

        val name =
            device
                .productName
                ?.toString()

        /*
         * Prefer SCO endpoint with same product name.
         */

        if (
            !name
                .isNullOrBlank()
        ) {

            devices
                .firstOrNull {
                    it.type ==
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                    it.productName
                        ?.toString() ==
                        name
                }
                ?.let {
                    return it
                }
        }

        /*
         * Otherwise accept the only SCO headset.
         */

        val scoDevices =
            devices.filter {
                it.type ==
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }

        return if (
            scoDevices.size == 1
        ) {

            scoDevices.first()

        } else {

            null
        }
    }

    /*
     * ---------------------------------------------------------
     * DEVICE TYPE CHECK
     * ---------------------------------------------------------
     */

    private fun isBluetoothCommunicationDevice(
        device:
            AudioDeviceInfo
    ): Boolean {

        return (
            device.type ==
                AudioDeviceInfo.TYPE_BLE_HEADSET ||
            device.type ==
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        )
    }

    /*
     * ---------------------------------------------------------
     * REFRESH DEVICE LIST
     * ---------------------------------------------------------
     */

    private fun refreshAvailableDevices() {

        val commDevices =
            audioManager
                .availableCommunicationDevices

        val btDevices =
            commDevices
                .filter { device ->

                    device.type ==
                        AudioDeviceInfo.TYPE_BLE_HEADSET ||

                    device.type ==
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||

                    device.type ==
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                }
                .sortedBy { device ->

                    /*
                     * BLE Headset first in UI/debug lists.
                     */

                    when (device.type) {

                        AudioDeviceInfo.TYPE_BLE_HEADSET ->
                            0

                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
                            1

                        else ->
                            2
                    }
                }
                .map { device ->

                    BluetoothAudioDevice(

                        deviceInfo =
                            device,

                        name =
                            device
                                .productName
                                ?.toString()
                                ?: "Unknown BT Device",

                        type =
                            device.type,

                        typeLabel =
                            deviceTypeToString(
                                device.type
                            )
                    )
                }

        _availableDevices.value =
            btDevices

        Logger.d(
            "Available BT devices: ${
                btDevices.map {
                    "${it.name} (${it.typeLabel})"
                }
            }"
        )
    }

    /*
     * ---------------------------------------------------------
     * CONSTANTS
     * ---------------------------------------------------------
     */

    companion object {

        /*
         * Fast enough to restore the route between
         * Voice Access recognition cycles.
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
                    "BT SCO / HFP"

                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ->
                    "BT A2DP"

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
