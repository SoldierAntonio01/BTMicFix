package com.btmicfix.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.core.content.getSystemService
import com.btmicfix.BuildConfig
import com.btmicfix.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Core audio routing manager.
 *
 * This is the primary mechanism for fixing Bluetooth mic routing.
 * It uses [AudioManager.setCommunicationDevice] (API 31+) to force the system
 * to use a Bluetooth device for communication audio input.
 *
 * The key insight: AI voice apps fail to trigger the A2DP → SCO/HFP profile
 * switch that enables the Bluetooth microphone. By calling setCommunicationDevice()
 * before the AI app starts recording, we force the system to make the switch.
 */
class AudioRoutingManager(private val context: Context) {

    private val audioManager: AudioManager =
        context.getSystemService<AudioManager>()
            ?: throw IllegalStateException("AudioManager not available")

    private val _routingState = MutableStateFlow<RoutingState>(RoutingState.Idle)
    val routingState: StateFlow<RoutingState> = _routingState.asStateFlow()

    private val _availableDevices = MutableStateFlow<List<BluetoothAudioDevice>>(emptyList())
    val availableDevices: StateFlow<List<BluetoothAudioDevice>> = _availableDevices.asStateFlow()

    private var currentRoutedDevice: AudioDeviceInfo? = null

    // Listen for device additions/removals
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            Logger.i("Audio devices added: ${addedDevices.map { deviceTypeToString(it.type) }}")
            refreshAvailableDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            Logger.i("Audio devices removed: ${removedDevices.map { deviceTypeToString(it.type) }}")
            // If our routed device was removed, clear routing
            currentRoutedDevice?.let { routed ->
                if (removedDevices.any { it.id == routed.id }) {
                    Logger.w("Routed device was removed, clearing routing")
                    clearRouting()
                }
            }
            refreshAvailableDevices()
        }
    }

    /**
     * Sealed class representing the current routing state.
     */
    sealed class RoutingState {
        data object Idle : RoutingState()
        data class Routing(val deviceName: String) : RoutingState()
        data class Active(val deviceName: String) : RoutingState()
        data class Failed(val reason: String) : RoutingState()
    }

    /**
     * Represents a Bluetooth audio device that can be used for communication.
     */
    data class BluetoothAudioDevice(
        val deviceInfo: AudioDeviceInfo,
        val name: String,
        val type: Int,
        val typeLabel: String,
    )

    /**
     * Start monitoring for audio device changes.
     * Call this when the app starts or the service wakes up.
     */
    fun startMonitoring() {
        Logger.i("Starting audio device monitoring")
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        refreshAvailableDevices()
    }

    /**
     * Stop monitoring for audio device changes.
     * Call this when the app is destroyed or the service sleeps.
     */
    fun stopMonitoring() {
        Logger.i("Stopping audio device monitoring")
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    /**
     * Attempt to route communication audio to the specified Bluetooth device.
     *
     * This sets the device as the preferred communication device, which triggers
     * the necessary A2DP → SCO/HFP profile switch that enables the microphone.
     *
     * @return The resulting [RoutingState] after the attempt.
     */
    fun routeToBluetooth(device: AudioDeviceInfo): RoutingState {
        val deviceName = device.productName?.toString() ?: "Bluetooth Device"
        Logger.i("Attempting to route to: $deviceName (type=${deviceTypeToString(device.type)})")

        _routingState.value = RoutingState.Routing(deviceName)

        try {
            // Set audio mode to communication — this is critical.
            // It tells the system we want two-way audio, not just media playback.
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            // This is the magic call — it forces the system to select this device
            // for communication audio, which triggers the SCO/HFP profile switch.
            val success = audioManager.setCommunicationDevice(device)

            if (success) {
                currentRoutedDevice = device
                val state = RoutingState.Active(deviceName)
                _routingState.value = state
                Logger.i("✓ Routing active: $deviceName")
                return state
            } else {
                val state = RoutingState.Failed("setCommunicationDevice returned false")
                _routingState.value = state
                Logger.e("✗ setCommunicationDevice failed for $deviceName")
                audioManager.mode = AudioManager.MODE_NORMAL
                return state
            }
        } catch (e: Exception) {
            val state = RoutingState.Failed(e.message ?: "Unknown error")
            _routingState.value = state
            Logger.e("✗ Exception during routing", e)
            audioManager.mode = AudioManager.MODE_NORMAL
            return state
        }
    }

    /**
     * Route to the first available Bluetooth communication device.
     * Convenience method for background service use.
     */
    fun routeToFirstAvailableBluetooth(): RoutingState {
        val btDevice = findFirstBluetoothCommunicationDevice()
        if (btDevice == null) {
            val state = RoutingState.Failed("No Bluetooth communication device found")
            _routingState.value = state
            Logger.w("No BT communication devices available")
            return state
        }
        return routeToBluetooth(btDevice)
    }

    /**
     * Route to a specific device by matching its address from preferences.
     */
    fun routeToDeviceByAddress(address: String): RoutingState {
        val targetDevice = getAvailableCommunicationDevices().find { deviceInfo ->
            deviceInfo.address == address
        }
        if (targetDevice == null) {
            val state = RoutingState.Failed("Paired device not found in available devices")
            _routingState.value = state
            Logger.w("Device with address ${if (BuildConfig.DEBUG) address else "REDACTED"} not found")
            return state
        }
        return routeToBluetooth(targetDevice)
    }

    /**
     * Clear the communication device routing, reverting to system defaults.
     */
    fun clearRouting() {
        Logger.i("Clearing audio routing")
        try {
            audioManager.clearCommunicationDevice()
            audioManager.mode = AudioManager.MODE_NORMAL
            currentRoutedDevice = null
            _routingState.value = RoutingState.Idle
            Logger.i("Routing cleared, back to system defaults")
        } catch (e: Exception) {
            Logger.e("Error clearing routing", e)
        }
    }

    /**
     * Get all available communication devices (these are devices the system
     * can use for two-way audio — specifically, devices that support SCO/HFP).
     */
    fun getAvailableCommunicationDevices(): List<AudioDeviceInfo> {
        return audioManager.availableCommunicationDevices
    }

    /**
     * Find the first Bluetooth device in the available communication devices.
     */
    fun findFirstBluetoothCommunicationDevice(): AudioDeviceInfo? {
        return audioManager.availableCommunicationDevices.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    }

    /**
     * Check if the currently set communication device is a Bluetooth device.
     */
    fun isBluetoothRouted(): Boolean {
        return currentRoutedDevice != null && _routingState.value is RoutingState.Active
    }

    /**
     * Refresh the list of available Bluetooth audio devices.
     */
    private fun refreshAvailableDevices() {
        val commDevices = audioManager.availableCommunicationDevices
        val btDevices = commDevices
            .filter { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
            .map { device ->
                BluetoothAudioDevice(
                    deviceInfo = device,
                    name = device.productName?.toString() ?: "Unknown BT Device",
                    type = device.type,
                    typeLabel = deviceTypeToString(device.type),
                )
            }

        _availableDevices.value = btDevices
        Logger.d("Available BT devices: ${btDevices.map { "${it.name} (${it.typeLabel})" }}")
    }

    companion object {
        /**
         * Convert AudioDeviceInfo type constant to a human-readable string.
         */
        fun deviceTypeToString(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT A2DP"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE Headset"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE Speaker"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in Speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Device"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            else -> "Type $type"
        }
    }
}
