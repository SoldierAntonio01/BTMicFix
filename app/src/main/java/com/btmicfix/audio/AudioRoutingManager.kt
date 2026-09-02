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
 * Core audio routing manager.
 *
 * Uses AudioManager.setCommunicationDevice() to force Bluetooth communication
 * audio and includes a watchdog that re-applies the route if another app
 * (for example Voice Access) drops or replaces it.
 */
class AudioRoutingManager(private val context: Context) {
    private val audioManager: AudioManager =
        context.getSystemService<AudioManager>()
            ?: throw IllegalStateException("AudioManager not available")

    private val _routingState = MutableStateFlow<RoutingState>(RoutingState.Idle)
    val routingState: StateFlow<RoutingState> = _routingState.asStateFlow()

    private val _availableDevices =
        MutableStateFlow<List<BluetoothAudioDevice>>(emptyList())
    val availableDevices: StateFlow<List<BluetoothAudioDevice>> =
        _availableDevices.asStateFlow()

    private var currentRoutedDevice: AudioDeviceInfo? = null

    // Route watchdog. It does NOT record audio; it only verifies/re-applies
    // the communication-device route.
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var watchdogRunning = false

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!watchdogRunning) return

            val rememberedTarget = currentRoutedDevice
            if (rememberedTarget == null) {
                stopRouteWatchdog()
                return
            }

            try {
                val target = resolveTargetDevice(rememberedTarget)
                val actual = audioManager.communicationDevice

                if (target != null) {
                    // Keep our remembered AudioDeviceInfo fresh in case Android
                    // recreated the SCO device with a different internal id.
                    currentRoutedDevice = target

                    val routeIsCorrect =
                        actual != null && samePhysicalDevice(actual, target)

                    val modeIsCorrect =
                        audioManager.mode == AudioManager.MODE_IN_COMMUNICATION

                    if (!routeIsCorrect || !modeIsCorrect) {
                        Logger.w(
                            "Watchdog: communication route dropped. " +
                                "actual=${actual?.productName} " +
                                "target=${target.productName}; re-applying"
                        )

                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

                        val success = audioManager.setCommunicationDevice(target)
                        if (success) {
                            val deviceName =
                                target.productName?.toString() ?: "Bluetooth Device"
                            _routingState.value = RoutingState.Active(deviceName)
                            Logger.i("✓ Watchdog restored BT communication route")
                        } else {
                            Logger.w("Watchdog retry returned false; will retry")
                        }
                    }
                } else {
                    Logger.w("Watchdog: target BT device temporarily unavailable")
                }
            } catch (e: Exception) {
                Logger.e("Watchdog route check failed", e)
            }

            if (watchdogRunning) {
                watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
    }

    // Listen for device additions/removals
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            Logger.i(
                "Audio devices added: ${
                    addedDevices.map { deviceTypeToString(it.type) }
                }"
            )
            refreshAvailableDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            Logger.i(
                "Audio devices removed: ${
                    removedDevices.map { deviceTypeToString(it.type) }
                }"
            )

            currentRoutedDevice?.let { routed ->
                // Do not immediately clear just because Android recreated the
                // SCO endpoint. Only clear if no matching BT communication
                // endpoint is available anymore.
                val routedWasRemoved = removedDevices.any { it.id == routed.id }
                if (routedWasRemoved && resolveTargetDevice(routed) == null) {
                    Logger.w("Routed device was removed, clearing routing")
                    clearRouting()
                }
            }

            refreshAvailableDevices()
        }
    }

    sealed class RoutingState {
        data object Idle : RoutingState()
        data class Routing(val deviceName: String) : RoutingState()
        data class Active(val deviceName: String) : RoutingState()
        data class Failed(val reason: String) : RoutingState()
    }

    data class BluetoothAudioDevice(
        val deviceInfo: AudioDeviceInfo,
        val name: String,
        val type: Int,
        val typeLabel: String,
    )

    fun startMonitoring() {
        Logger.i("Starting audio device monitoring")
        try {
            audioManager.registerAudioDeviceCallback(deviceCallback, null)
        } catch (e: Exception) {
            Logger.w("Audio callback may already be registered")
        }
        refreshAvailableDevices()
    }

    fun stopMonitoring() {
        Logger.i("Stopping audio device monitoring")
        try {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        } catch (e: Exception) {
            // Ignore if already unregistered.
        }
    }

    fun routeToBluetooth(device: AudioDeviceInfo): RoutingState {
        val deviceName = device.productName?.toString() ?: "Bluetooth Device"
        Logger.i(
            "Attempting to route to: $deviceName " +
                "(type=${deviceTypeToString(device.type)})"
        )
        _routingState.value = RoutingState.Routing(deviceName)

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            val success = audioManager.setCommunicationDevice(device)

            if (success) {
                currentRoutedDevice = device
                val state = RoutingState.Active(deviceName)
                _routingState.value = state
                startRouteWatchdog()
                Logger.i("✓ Routing active: $deviceName (watchdog enabled)")
                return state
            } else {
                stopRouteWatchdog()
                val state =
                    RoutingState.Failed("setCommunicationDevice returned false")
                _routingState.value = state
                Logger.e("✗ setCommunicationDevice failed for $deviceName")
                audioManager.mode = AudioManager.MODE_NORMAL
                return state
            }
        } catch (e: Exception) {
            stopRouteWatchdog()
            val state = RoutingState.Failed(e.message ?: "Unknown error")
            _routingState.value = state
            Logger.e("✗ Exception during routing", e)
            audioManager.mode = AudioManager.MODE_NORMAL
            return state
        }
    }

    fun routeToFirstAvailableBluetooth(): RoutingState {
        val btDevice = findFirstBluetoothCommunicationDevice()

        if (btDevice == null) {
            val state =
                RoutingState.Failed("No Bluetooth communication device found")
            _routingState.value = state
            Logger.w("No BT communication devices available")
            return state
        }

        return routeToBluetooth(btDevice)
    }

    fun routeToDeviceByAddress(address: String): RoutingState {
        val targetDevice =
            getAvailableCommunicationDevices().find { deviceInfo ->
                deviceInfo.address == address
            }

        if (targetDevice == null) {
            val state =
                RoutingState.Failed(
                    "Paired device not found in available devices"
                )
            _routingState.value = state
            Logger.w(
                "Device with address ${
                    if (BuildConfig.DEBUG) address else "REDACTED"
                } not found"
            )
            return state
        }

        return routeToBluetooth(targetDevice)
    }

    fun clearRouting() {
        Logger.i("Clearing audio routing")
        stopRouteWatchdog()

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

    fun getAvailableCommunicationDevices(): List<AudioDeviceInfo> {
        return audioManager.availableCommunicationDevices
    }

    fun findFirstBluetoothCommunicationDevice(): AudioDeviceInfo? {
        return audioManager.availableCommunicationDevices.firstOrNull { device ->
            isBluetoothCommunicationDevice(device)
        }
    }

    /**
     * Real route check. Unlike the original implementation, this verifies the
     * AudioManager's current communication device instead of only trusting
     * BTMicFix's stored state.
     */
    fun isBluetoothRouted(): Boolean {
        val target = currentRoutedDevice ?: return false
        val actual = audioManager.communicationDevice ?: return false

        return _routingState.value is RoutingState.Active &&
            samePhysicalDevice(actual, target)
    }

    private fun startRouteWatchdog() {
        if (watchdogRunning) return

        watchdogRunning = true
        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.post(watchdogRunnable)
        Logger.i("Route watchdog started")
    }

    private fun stopRouteWatchdog() {
        watchdogRunning = false
        watchdogHandler.removeCallbacks(watchdogRunnable)
        Logger.i("Route watchdog stopped")
    }

    /**
     * Find the currently available version of the remembered target device.
     * Android/Samsung may recreate SCO endpoints and give them a new internal id.
     */
    private fun resolveTargetDevice(
        remembered: AudioDeviceInfo
    ): AudioDeviceInfo? {
        val devices =
            audioManager.availableCommunicationDevices.filter {
                isBluetoothCommunicationDevice(it)
            }

        devices.firstOrNull { it.id == remembered.id }?.let { return it }

        if (remembered.address.isNotBlank()) {
            devices.firstOrNull {
                it.address == remembered.address
            }?.let { return it }
        }

        val rememberedName = remembered.productName?.toString()
        if (!rememberedName.isNullOrBlank()) {
            devices.firstOrNull {
                it.productName?.toString() == rememberedName
            }?.let { return it }
        }

        // If there is only one BT communication endpoint, it is almost
        // certainly the same connected headset.
        return if (devices.size == 1) devices.first() else null
    }

    private fun samePhysicalDevice(
        first: AudioDeviceInfo,
        second: AudioDeviceInfo
    ): Boolean {
        if (first.id == second.id) return true

        if (
            first.address.isNotBlank() &&
            second.address.isNotBlank() &&
            first.address == second.address
        ) {
            return true
        }

        val firstName = first.productName?.toString()
        val secondName = second.productName?.toString()

        return !firstName.isNullOrBlank() &&
            firstName == secondName &&
            isBluetoothCommunicationDevice(first) &&
            isBluetoothCommunicationDevice(second)
    }

    private fun isBluetoothCommunicationDevice(
        device: AudioDeviceInfo
    ): Boolean {
        return device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }

    private fun refreshAvailableDevices() {
        val commDevices = audioManager.availableCommunicationDevices

        val btDevices =
            commDevices
                .filter { device ->
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                }
                .map { device ->
                    BluetoothAudioDevice(
                        deviceInfo = device,
                        name =
                            device.productName?.toString()
                                ?: "Unknown BT Device",
                        type = device.type,
                        typeLabel = deviceTypeToString(device.type),
                    )
                }

        _availableDevices.value = btDevices

        Logger.d(
            "Available BT devices: ${
                btDevices.map { "${it.name} (${it.typeLabel})" }
            }"
        )
    }

    companion object {
        // Fast enough to recover between Voice Access recognition cycles
        // without constantly re-setting the route when it is already correct.
        private const val WATCHDOG_INTERVAL_MS = 250L

        fun deviceTypeToString(type: Int): String =
            when (type) {
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
