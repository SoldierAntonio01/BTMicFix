package com.btmicfix.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.getSystemService
import com.btmicfix.BuildConfig
import com.btmicfix.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BTMicFix BLE Audio routing manager.
 *
 * CURRENT DESIGN:
 *
 * - Strict BLE Headset / LE Audio routing
 * - No permanent 250 ms polling watchdog
 * - Android communication-device callbacks are used instead
 * - 150 ms soft route recovery
 * - 1 second recovery cooldown
 * - 6 second BLE endpoint disconnect confirmation
 * - Shared process-wide RoutingState
 *
 * The shared RoutingState is important because the background
 * Voice Access automation service and HomeScreen each have their
 * own AudioRoutingManager instance.
 *
 * When the automation turns routing on:
 *
 *      HomeScreen -> Active
 *
 * When Voice Access stops and automation clears routing:
 *
 *      HomeScreen -> Idle
 */
class AudioRoutingManager(
    private val context: Context
) {

    /*
     * ============================================================
     * AUDIO MANAGER
     * ============================================================
     */

    private val audioManager: AudioManager =
        context.getSystemService<AudioManager>()
            ?: throw IllegalStateException(
                "AudioManager not available"
            )

    /*
     * ============================================================
     * SHARED ROUTING STATE
     * ============================================================
     */

    private val _routingState =
        sharedRoutingState

    val routingState:
        StateFlow<RoutingState> =
        sharedRoutingState.asStateFlow()

    /*
     * ============================================================
     * AVAILABLE DEVICES
     * ============================================================
     */

    private val _availableDevices =
        MutableStateFlow<
            List<BluetoothAudioDevice>
        >(
            emptyList()
        )

    val availableDevices:
        StateFlow<List<BluetoothAudioDevice>> =
        _availableDevices.asStateFlow()

    /*
     * ============================================================
     * INSTANCE ROUTE TARGET
     * ============================================================
     */

    private var currentRoutedDevice:
        AudioDeviceInfo? =
        null

    private var monitoring =
        false

    /*
     * ============================================================
     * MAIN HANDLER
     * ============================================================
     */

    private val handler =
        Handler(
            Looper.getMainLooper()
        )

    /*
     * ============================================================
     * RECOVERY STATE
     * ============================================================
     */

    private var restoreScheduled =
        false

    private var disconnectCheckScheduled =
        false

    private var lastRestoreAttemptMs =
        0L

    /*
     * ============================================================
     * MODELS
     * ============================================================
     */

    sealed class RoutingState {

        data object Idle :
            RoutingState()

        data class Routing(
            val deviceName: String
        ) :
            RoutingState()

        data class Active(
            val deviceName: String
        ) :
            RoutingState()

        data class Failed(
            val reason: String
        ) :
            RoutingState()
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
     * ROUTE RESTORE RUNNABLE
     * ============================================================
     *
     * This runs only after Android actually reports a route change.
     *
     * There is NO repeating polling loop.
     */

    private val restoreRunnable =
        Runnable {

            restoreScheduled =
                false

            val remembered =
                currentRoutedDevice
                    ?: return@Runnable

            /*
             * Android can recreate AudioDeviceInfo objects.
             *
             * Find the newest representation of the same BLE Buds.
             */

            val target =
                resolveBleTargetDevice(
                    remembered
                )

            if (
                target ==
                null
            ) {

                Logger.w(
                    "BLE route target temporarily unavailable"
                )

                scheduleDisconnectCheck()

                return@Runnable
            }

            currentRoutedDevice =
                target

            /*
             * Check where Android is already routed.
             */

            val actual =
                try {

                    audioManager.communicationDevice

                } catch (_: Throwable) {

                    null
                }

            /*
             * Android already restored the route itself.
             *
             * Do nothing.
             */

            if (
                actual != null &&
                sameBleDevice(
                    actual,
                    target
                )
            ) {

                _routingState.value =
                    RoutingState.Active(
                        buildDisplayName(
                            target
                        )
                    )

                Logger.i(
                    "BLE route already restored by Android"
                )

                return@Runnable
            }

            /*
             * ====================================================
             * RECOVERY COOLDOWN
             * ====================================================
             */

            val now =
                SystemClock.elapsedRealtime()

            val elapsed =
                now -
                    lastRestoreAttemptMs

            if (
                elapsed <
                RESTORE_COOLDOWN_MS
            ) {

                val remaining =
                    RESTORE_COOLDOWN_MS -
                        elapsed

                scheduleRestore(
                    remaining
                )

                return@Runnable
            }

            lastRestoreAttemptMs =
                now

            /*
             * ====================================================
             * SOFT RECOVERY FIRST
             * ====================================================
             */

            try {

                Logger.i(
                    "Communication route changed away from BLE. " +
                        "Trying soft recovery."
                )

                /*
                 * First attempt:
                 *
                 * Do NOT rewrite AudioManager.mode.
                 *
                 * Just select the BLE communication endpoint again.
                 */

                var success =
                    audioManager
                        .setCommunicationDevice(
                            target
                        )

                /*
                 * =================================================
                 * HEAVIER FALLBACK
                 * =================================================
                 *
                 * Only touch MODE_IN_COMMUNICATION if the soft
                 * recovery was rejected.
                 */

                if (!success) {

                    Logger.w(
                        "Soft BLE recovery failed. " +
                            "Trying communication-mode fallback."
                    )

                    if (
                        audioManager.mode !=
                        AudioManager.MODE_IN_COMMUNICATION
                    ) {

                        audioManager.mode =
                            AudioManager.MODE_IN_COMMUNICATION
                    }

                    success =
                        audioManager
                            .setCommunicationDevice(
                                target
                            )
                }

                if (success) {

                    currentRoutedDevice =
                        target

                    _routingState.value =
                        RoutingState.Active(
                            buildDisplayName(
                                target
                            )
                        )

                    Logger.i(
                        "BLE route recovery accepted"
                    )

                } else {

                    Logger.w(
                        "BLE route recovery returned false"
                    )
                }

            } catch (e: Throwable) {

                Logger.e(
                    "BLE route recovery failed",
                    e
                )
            }
        }

    /*
     * ============================================================
     * DISCONNECT CONFIRMATION
     * ============================================================
     *
     * Samsung may briefly remove and recreate TYPE_BLE_HEADSET.
     *
     * We wait before treating that as a true Buds disconnect.
     */

    private val disconnectCheckRunnable =
        Runnable {

            disconnectCheckScheduled =
                false

            val remembered =
                currentRoutedDevice
                    ?: return@Runnable

            val replacement =
                resolveBleTargetDevice(
                    remembered
                )

            if (
                replacement != null
            ) {

                currentRoutedDevice =
                    replacement

                Logger.i(
                    "BLE endpoint returned after temporary change"
                )

                scheduleRestore(
                    RESTORE_DEBOUNCE_MS
                )

                return@Runnable
            }

            Logger.w(
                "BLE endpoint still unavailable after confirmation delay"
            )

            clearRouting()
        }

    /*
     * ============================================================
     * COMMUNICATION DEVICE LISTENER
     * ============================================================
     */

    private val communicationDeviceChangedListener =
        AudioManager
            .OnCommunicationDeviceChangedListener {
                    device ->

                handleCommunicationDeviceChanged(
                    device
                )
            }

    private fun handleCommunicationDeviceChanged(
        device: AudioDeviceInfo?
    ) {

        val target =
            currentRoutedDevice
                ?: return

        /*
         * Still routed correctly.
         */

        if (
            device != null &&
                sameBleDevice(
                    device,
                    target
                )
        ) {

            cancelRestore()

            currentRoutedDevice =
                device

            lastRestoreAttemptMs =
                0L

            _routingState.value =
                RoutingState.Active(
                    buildDisplayName(
                        device
                    )
                )

            return
        }

        /*
         * Android actually changed away from the BLE route.
         */

        Logger.i(
            "Communication route changed away from BLE"
        )

        scheduleRestore(
            RESTORE_DEBOUNCE_MS
        )
    }

    /*
     * ============================================================
     * AUDIO DEVICE CALLBACK
     * ============================================================
     */

    private val deviceCallback =
        object :
            AudioDeviceCallback() {

            override fun onAudioDevicesAdded(
                addedDevices:
                    Array<out AudioDeviceInfo>
            ) {

                refreshAvailableDevices()

                /*
                 * Android may have recreated our BLE endpoint.
                 */

                if (
                    currentRoutedDevice != null
                ) {

                    scheduleRestore(
                        RESTORE_DEBOUNCE_MS
                    )
                }
            }

            override fun onAudioDevicesRemoved(
                removedDevices:
                    Array<out AudioDeviceInfo>
            ) {

                refreshAvailableDevices()

                val target =
                    currentRoutedDevice
                        ?: return

                val removed =
                    removedDevices.any {

                        it.id ==
                            target.id
                    }

                if (removed) {

                    Logger.i(
                        "Current BLE endpoint disappeared temporarily"
                    )

                    scheduleDisconnectCheck()
                }
            }
        }

    /*
     * ============================================================
     * START MONITORING
     * ============================================================
     */

    fun startMonitoring() {

        if (monitoring) {

            return
        }

        monitoring =
            true

        Logger.i(
            "Starting event-driven BLE audio monitoring"
        )

        try {

            audioManager
                .registerAudioDeviceCallback(
                    deviceCallback,
                    null
                )

        } catch (e: Throwable) {

            Logger.w(
                "Audio device callback failed: ${e.message}"
            )
        }

        try {

            audioManager
                .addOnCommunicationDeviceChangedListener(
                    context.mainExecutor,
                    communicationDeviceChangedListener
                )

        } catch (e: Throwable) {

            Logger.e(
                "Communication device listener failed",
                e
            )
        }

        refreshAvailableDevices()
    }

    /*
     * ============================================================
     * STOP MONITORING
     * ============================================================
     */

    fun stopMonitoring() {

        if (!monitoring) {

            return
        }

        monitoring =
            false

        cancelRestore()
        cancelDisconnectCheck()

        try {

            audioManager
                .unregisterAudioDeviceCallback(
                    deviceCallback
                )

        } catch (_: Throwable) {
        }

        try {

            audioManager
                .removeOnCommunicationDeviceChangedListener(
                    communicationDeviceChangedListener
                )

        } catch (_: Throwable) {
        }

        Logger.i(
            "Stopped BLE audio monitoring"
        )
    }

    /*
     * ============================================================
     * ROUTE TO BLUETOOTH
     * ============================================================
     *
     * STRICT LE AUDIO:
     *
     * TYPE_BLE_HEADSET only.
     *
     * No HFP/SCO fallback.
     */

    fun routeToBluetooth(
        requestedDevice:
            AudioDeviceInfo
    ): RoutingState {

        val target =
            findMatchingBleDevice(
                requestedDevice
            )

        if (
            target == null
        ) {

            val state =
                RoutingState.Failed(
                    "BLE Headset / LE Audio route is not available"
                )

            _routingState.value =
                state

            Logger.w(
                "TYPE_BLE_HEADSET unavailable"
            )

            return state
        }

        val displayName =
            buildDisplayName(
                target
            )

        _routingState.value =
            RoutingState.Routing(
                displayName
            )

        cancelRestore()
        cancelDisconnectCheck()

        try {

            /*
             * Initial activation.
             */

            if (
                audioManager.mode !=
                AudioManager.MODE_IN_COMMUNICATION
            ) {

                audioManager.mode =
                    AudioManager.MODE_IN_COMMUNICATION
            }

            val success =
                audioManager
                    .setCommunicationDevice(
                        target
                    )

            if (success) {

                currentRoutedDevice =
                    target

                lastRestoreAttemptMs =
                    SystemClock.elapsedRealtime()

                val state =
                    RoutingState.Active(
                        displayName
                    )

                _routingState.value =
                    state

                Logger.i(
                    "BLE communication route active: $displayName"
                )

                return state
            }

            /*
             * Failed.
             */

            currentRoutedDevice =
                null

            try {

                audioManager.mode =
                    AudioManager.MODE_NORMAL

            } catch (_: Throwable) {
            }

            val state =
                RoutingState.Failed(
                    "setCommunicationDevice returned false"
                )

            _routingState.value =
                state

            return state

        } catch (e: Throwable) {

            currentRoutedDevice =
                null

            try {

                audioManager.mode =
                    AudioManager.MODE_NORMAL

            } catch (_: Throwable) {
            }

            val state =
                RoutingState.Failed(
                    e.message
                        ?: "Unknown BLE routing error"
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
     * FIRST AVAILABLE BLE HEADSET
     * ============================================================
     */

    fun routeToFirstAvailableBluetooth():
        RoutingState {

        val target =
            findFirstBluetoothCommunicationDevice()

        if (
            target == null
        ) {

            val state =
                RoutingState.Failed(
                    "No BLE Headset communication device found"
                )

            _routingState.value =
                state

            return state
        }

        return routeToBluetooth(
            target
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

        val devices =
            audioManager
                .availableCommunicationDevices

        /*
         * Exact BLE address.
         */

        val exactBle =
            devices
                .firstOrNull {

                    it.type ==
                        AudioDeviceInfo.TYPE_BLE_HEADSET &&
                        it.address ==
                        address
                }

        if (
            exactBle != null
        ) {

            return routeToBluetooth(
                exactBle
            )
        }

        /*
         * Samsung may recreate the endpoint and mask/change
         * its visible address.
         *
         * If exactly one BLE headset exists, use it.
         */

        val bleDevices =
            devices.filter {

                it.type ==
                    AudioDeviceInfo.TYPE_BLE_HEADSET
            }

        if (
            bleDevices.size ==
            1
        ) {

            return routeToBluetooth(
                bleDevices.first()
            )
        }

        val state =
            RoutingState.Failed(
                "Saved BLE headset was not found"
            )

        _routingState.value =
            state

        Logger.w(
            "Saved BLE address ${
                if (BuildConfig.DEBUG) {
                    address
                } else {
                    "REDACTED"
                }
            } unavailable"
        )

        return state
    }

    /*
     * ============================================================
     * CLEAR ROUTING
     * ============================================================
     */

    fun clearRouting() {

        cancelRestore()
        cancelDisconnectCheck()

        /*
         * Clear our remembered target first.
         *
         * Otherwise the communication callback generated by
         * clearCommunicationDevice() could restore it.
         */

        currentRoutedDevice =
            null

        lastRestoreAttemptMs =
            0L

        try {

            audioManager
                .clearCommunicationDevice()

        } catch (e: Throwable) {

            Logger.w(
                "clearCommunicationDevice failed: ${e.message}"
            )
        }

        try {

            audioManager.mode =
                AudioManager.MODE_NORMAL

        } catch (_: Throwable) {
        }

        /*
         * Shared state also changes the HomeScreen immediately.
         */

        _routingState.value =
            RoutingState.Idle

        Logger.i(
            "BLE communication route released"
        )
    }

    /*
     * ============================================================
     * AVAILABLE COMMUNICATION DEVICES
     * ============================================================
     */

    fun getAvailableCommunicationDevices():
        List<AudioDeviceInfo> {

        return audioManager
            .availableCommunicationDevices
    }

    /*
     * ============================================================
     * FIND BLE HEADSET
     * ============================================================
     */

    fun findFirstBluetoothCommunicationDevice():
        AudioDeviceInfo? {

        /*
         * Strict BLE.
         *
         * Do NOT silently return HFP/SCO.
         */

        return audioManager
            .availableCommunicationDevices
            .firstOrNull {

                it.type ==
                    AudioDeviceInfo.TYPE_BLE_HEADSET
            }
    }

    /*
     * ============================================================
     * IS ACTUALLY ROUTED?
     * ============================================================
     *
     * This is the function that caused the previous compiler error.
     *
     * Keep the parentheses exactly like this.
     */

    fun isBluetoothRouted(): Boolean {

        val target =
            currentRoutedDevice
                ?: return false

        val actual =
            try {

                audioManager.communicationDevice

            } catch (_: Throwable) {

                null
            }
                ?: return false

        return (
            _routingState.value is RoutingState.Active
        ) && sameBleDevice(
            actual,
            target
        )
    }

    /*
     * ============================================================
     * SCHEDULE RESTORE
     * ============================================================
     */

    private fun scheduleRestore(
        delayMs: Long =
            RESTORE_DEBOUNCE_MS
    ) {

        if (
            currentRoutedDevice == null
        ) {

            return
        }

        /*
         * Don't stack recovery events.
         */

        if (
            restoreScheduled
        ) {

            return
        }

        restoreScheduled =
            true

        handler.postDelayed(
            restoreRunnable,
            delayMs
        )
    }

    private fun cancelRestore() {

        restoreScheduled =
            false

        handler.removeCallbacks(
            restoreRunnable
        )
    }

    /*
     * ============================================================
     * DISCONNECT CONFIRMATION
     * ============================================================
     */

    private fun scheduleDisconnectCheck() {

        if (
            disconnectCheckScheduled
        ) {

            return
        }

        disconnectCheckScheduled =
            true

        handler.postDelayed(
            disconnectCheckRunnable,
            DISCONNECT_CONFIRM_MS
        )
    }

    private fun cancelDisconnectCheck() {

        disconnectCheckScheduled =
            false

        handler.removeCallbacks(
            disconnectCheckRunnable
        )
    }

    /*
     * ============================================================
     * MATCH REQUESTED DEVICE TO BLE ENDPOINT
     * ============================================================
     */

    private fun findMatchingBleDevice(
        original:
            AudioDeviceInfo
    ): AudioDeviceInfo? {

        val bleDevices =
            audioManager
                .availableCommunicationDevices
                .filter {

                    it.type ==
                        AudioDeviceInfo.TYPE_BLE_HEADSET
                }

        /*
         * Same Android audio endpoint.
         */

        if (
            original.type ==
            AudioDeviceInfo.TYPE_BLE_HEADSET
        ) {

            bleDevices
                .firstOrNull {

                    it.id ==
                        original.id
                }
                ?.let {

                    return it
                }
        }

        /*
         * Match address.
         */

        if (
            original.address.isNotBlank()
        ) {

            bleDevices
                .firstOrNull {

                    it.address ==
                        original.address
                }
                ?.let {

                    return it
                }
        }

        /*
         * Match device name.
         */

        val originalName =
            original
                .productName
                ?.toString()

        if (
            !originalName.isNullOrBlank()
        ) {

            bleDevices
                .firstOrNull {

                    it.productName
                        ?.toString() ==
                        originalName
                }
                ?.let {

                    return it
                }
        }

        /*
         * Exactly one BLE headset connected.
         */

        return if (
            bleDevices.size ==
            1
        ) {

            bleDevices.first()

        } else {

            null
        }
    }

    /*
     * ============================================================
     * RESOLVE REMEMBERED BLE DEVICE
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
                        AudioDeviceInfo.TYPE_BLE_HEADSET
                }

        /*
         * Same Android ID.
         */

        bleDevices
            .firstOrNull {

                it.id ==
                    remembered.id
            }
            ?.let {

                return it
            }

        /*
         * Same address.
         */

        if (
            remembered.address.isNotBlank()
        ) {

            bleDevices
                .firstOrNull {

                    it.address ==
                        remembered.address
                }
                ?.let {

                    return it
                }
        }

        /*
         * Same product name.
         */

        val rememberedName =
            remembered
                .productName
                ?.toString()

        if (
            !rememberedName.isNullOrBlank()
        ) {

            bleDevices
                .firstOrNull {

                    it.productName
                        ?.toString() ==
                        rememberedName
                }
                ?.let {

                    return it
                }
        }

        /*
         * Exactly one BLE headset.
         */

        return if (
            bleDevices.size ==
            1
        ) {

            bleDevices.first()

        } else {

            null
        }
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
         * Both must be TYPE_BLE_HEADSET.
         */

        if (
            first.type !=
            AudioDeviceInfo.TYPE_BLE_HEADSET ||
            second.type !=
            AudioDeviceInfo.TYPE_BLE_HEADSET
        ) {

            return false
        }

        /*
         * Exact Android device ID.
         */

        if (
            first.id ==
            second.id
        ) {

            return true
        }

        /*
         * Exact address.
         */

        if (
            first.address.isNotBlank() &&
            second.address.isNotBlank() &&
            first.address ==
            second.address
        ) {

            return true
        }

        /*
         * Device name.
         */

        val firstName =
            first
                .productName
                ?.toString()

        val secondName =
            second
                .productName
                ?.toString()

        return (
            !firstName.isNullOrBlank() &&
            firstName ==
            secondName
        )
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

        val bluetoothDevices =
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

                    when (
                        it.type
                    ) {

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
                .map { device ->

                    BluetoothAudioDevice(

                        deviceInfo =
                            device,

                        name =
                            device
                                .productName
                                ?.toString()
                                ?: "Unknown Bluetooth Device",

                        type =
                            device.type,

                        typeLabel =
                            deviceTypeToString(
                                device.type
                            )
                    )
                }

        /*
         * Avoid unnecessary StateFlow updates and Compose redraws.
         */

        val oldSummary =
            _availableDevices.value
                .map {

                    Triple(
                        it.deviceInfo.id,
                        it.name,
                        it.type
                    )
                }

        val newSummary =
            bluetoothDevices
                .map {

                    Triple(
                        it.deviceInfo.id,
                        it.name,
                        it.type
                    )
                }

        if (
            oldSummary !=
            newSummary
        ) {

            _availableDevices.value =
                bluetoothDevices
        }
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
         * Shared between the HomeScreen manager and
         * VoiceAccessMonitorService manager.
         */

        private val sharedRoutingState =
            MutableStateFlow<RoutingState>(
                RoutingState.Idle
            )

        /*
         * Fast route recovery.
         */

        private const val
            RESTORE_DEBOUNCE_MS =
            150L

        /*
         * Prevent repeated AudioService calls.
         */

        private const val
            RESTORE_COOLDOWN_MS =
            1000L

        /*
         * Give Samsung time to recreate a BLE endpoint.
         */

        private const val
            DISCONNECT_CONFIRM_MS =
            6000L

        /*
         * ========================================================
         * DEVICE LABEL
         * ========================================================
         */

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
