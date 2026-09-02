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
 * Low-overhead BLE Audio routing manager.
 *
 *
 * IMPORTANT:
 *
 * - NO permanent 250 ms watchdog
 * - event-driven communication-device monitoring
 * - fast 150 ms recovery
 * - soft recovery first
 * - MODE_IN_COMMUNICATION only rewritten if necessary
 * - strict TYPE_BLE_HEADSET routing
 *
 *
 * NEW:
 *
 * RoutingState is shared between every AudioRoutingManager
 * instance in this app process.
 *
 * Therefore:
 *
 * Background Voice Access service turns routing on
 *
 *      ↓
 *
 * HomeScreen immediately sees Active
 *
 * and:
 *
 * Voice Access stops
 *
 *      ↓
 *
 * background service clears route
 *
 *      ↓
 *
 * HomeScreen immediately sees Idle
 */
class AudioRoutingManager(
    private val context: Context
) {

    private val audioManager:
        AudioManager =
        context
            .getSystemService<AudioManager>()
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
        sharedRoutingState
            .asStateFlow()

    /*
     * ============================================================
     * INSTANCE DEVICE LIST
     * ============================================================
     */

    private val _availableDevices =
        MutableStateFlow<
            List<BluetoothAudioDevice>
        >(
            emptyList()
        )

    val availableDevices:
        StateFlow<
            List<BluetoothAudioDevice>
        > =
        _availableDevices
            .asStateFlow()

    /*
     * ============================================================
     * CURRENT ROUTE OWNED BY THIS MANAGER INSTANCE
     * ============================================================
     */

    private var currentRoutedDevice:
        AudioDeviceInfo? =
        null

    private var monitoring =
        false

    /*
     * ============================================================
     * HANDLER / RECOVERY
     * ============================================================
     */

    private val handler =
        Handler(
            Looper.getMainLooper()
        )

    private var restoreScheduled =
        false

    private var disconnectCheckScheduled =
        false

    private var lastRestoreAttemptMs =
        0L

    /*
     * ============================================================
     * FAST SOFT RECOVERY
     * ============================================================
     */

    private val restoreRunnable =
        Runnable {

            restoreScheduled =
                false

            val remembered =
                currentRoutedDevice
                    ?: return@Runnable

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

            val actual =
                try {

                    audioManager
                        .communicationDevice

                } catch (
                    _: Throwable
                ) {

                    null
                }

            /*
             * Android already fixed the route by itself.
             */

            if (
                actual !=
                null &&

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

                return@Runnable
            }

            /*
             * Cooldown avoids hammering AudioService.
             */

            val now =
                SystemClock
                    .elapsedRealtime()

            val elapsed =
                now -
                    lastRestoreAttemptMs

            if (
                elapsed <
                RESTORE_COOLDOWN_MS
            ) {

                scheduleRestore(
                    RESTORE_COOLDOWN_MS -
                        elapsed
                )

                return@Runnable
            }

            lastRestoreAttemptMs =
                now

            try {

                /*
                 * =================================================
                 * SOFT RECOVERY FIRST
                 * =================================================
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
                 */

                if (
                    !success
                ) {

                    if (
                        audioManager.mode !=
                        AudioManager
                            .MODE_IN_COMMUNICATION
                    ) {

                        audioManager.mode =
                            AudioManager
                                .MODE_IN_COMMUNICATION
                    }

                    success =
                        audioManager
                            .setCommunicationDevice(
                                target
                            )
                }

                if (
                    success
                ) {

                    _routingState.value =
                        RoutingState.Active(
                            buildDisplayName(
                                target
                            )
                        )

                    Logger.i(
                        "✓ BLE route recovery accepted"
                    )

                } else {

                    Logger.w(
                        "BLE route recovery returned false"
                    )
                }

            } catch (
                e: Throwable
            ) {

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
                replacement !=
                null
            ) {

                currentRoutedDevice =
                    replacement

                scheduleRestore(
                    RESTORE_DEBOUNCE_MS
                )

                return@Runnable
            }

            Logger.w(
                "BLE endpoint still unavailable after confirmation"
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

        if (
            device !=
            null &&

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
         * Android actually changed away from our target.
         */

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

                if (
                    currentRoutedDevice !=
                    null
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

                val targetRemoved =
                    removedDevices
                        .any {

                            it.id ==
                                target.id
                        }

                if (
                    targetRemoved
                ) {

                    scheduleDisconnectCheck()
                }
            }
        }

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
     * MONITOR
     * ============================================================
     */

    fun startMonitoring() {

        if (
            monitoring
        ) {

            return
        }

        monitoring =
            true

        try {

            audioManager
                .registerAudioDeviceCallback(
                    deviceCallback,
                    null
                )

        } catch (
            e: Throwable
        ) {

            Logger.w(
                "Audio device callback registration failed: " +
                    e.message
            )
        }

        try {

            audioManager
                .addOnCommunicationDeviceChangedListener(
                    context.mainExecutor,
                    communicationDeviceChangedListener
                )

        } catch (
            e: Throwable
        ) {

            Logger.e(
                "Communication-device listener registration failed",
                e
            )
        }

        refreshAvailableDevices()
    }

    fun stopMonitoring() {

        if (
            !monitoring
        ) {

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

        } catch (
            _: Throwable
        ) {
        }

        try {

            audioManager
                .removeOnCommunicationDeviceChangedListener(
                    communicationDeviceChangedListener
                )

        } catch (
            _: Throwable
        ) {
        }
    }

    /*
     * ============================================================
     * ROUTE TO BLE HEADSET
     * ============================================================
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
            target ==
            null
        ) {

            val state =
                RoutingState.Failed(
                    "BLE Headset / LE Audio route is not available"
                )

            _routingState.value =
                state

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
             * Initial activation needs communication mode.
             */

            if (
                audioManager.mode !=
                AudioManager
                    .MODE_IN_COMMUNICATION
            ) {

                audioManager.mode =
                    AudioManager
                        .MODE_IN_COMMUNICATION
            }

            val success =
                audioManager
                    .setCommunicationDevice(
                        target
                    )

            if (
                success
            ) {

                currentRoutedDevice =
                    target

                lastRestoreAttemptMs =
                    SystemClock
                        .elapsedRealtime()

                val state =
                    RoutingState.Active(
                        displayName
                    )

                _routingState.value =
                    state

                Logger.i(
                    "✓ BLE route active: $displayName"
                )

                return state
            }

            currentRoutedDevice =
                null

            try {

                audioManager.mode =
                    AudioManager
                        .MODE_NORMAL

            } catch (
                _: Throwable
            ) {
            }

            val state =
                RoutingState.Failed(
                    "setCommunicationDevice returned false"
                )

            _routingState.value =
                state

            return state

        } catch (
            e: Throwable
        ) {

            currentRoutedDevice =
                null

            try {

                audioManager.mode =
                    AudioManager
                        .MODE_NORMAL

            } catch (
                _: Throwable
            ) {
            }

            val state =
                RoutingState.Failed(
                    e.message
                        ?: "Unknown BLE routing error"
                )

            _routingState.value =
                state

            Logger.e(
                "BLE routing failed",
                e
            )

            return state
        }
    }

    /*
     * ============================================================
     * FIRST BLE HEADSET
     * ============================================================
     */

    fun routeToFirstAvailableBluetooth():
        RoutingState {

        val target =
            findFirstBluetoothCommunicationDevice()

        if (
            target ==
            null
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
     * SAVED ADDRESS
     * ============================================================
     */

    fun routeToDeviceByAddress(
        address: String
    ): RoutingState {

        val devices =
            audioManager
                .availableCommunicationDevices

        val exact =
            devices
                .firstOrNull {

                    it.type ==
                        AudioDeviceInfo
                            .TYPE_BLE_HEADSET &&

                        it.address ==
                        address
                }

        if (
            exact !=
            null
        ) {

            return routeToBluetooth(
                exact
            )
        }

        val ble =
            devices.filter {

                it.type ==
                    AudioDeviceInfo
                        .TYPE_BLE_HEADSET
            }

        if (
            ble.size ==
            1
        ) {

            return routeToBluetooth(
                ble.first()
            )
        }

        val state =
            RoutingState.Failed(
                "Saved BLE headset was not found"
            )

        _routingState.value =
            state

        Logger.w(
            "BLE address ${
                if (
                    BuildConfig.DEBUG
                ) {
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
         * Clear our target before changing AudioManager.
         *
         * Otherwise our listener could immediately restore it.
         */

        currentRoutedDevice =
            null

        lastRestoreAttemptMs =
            0L

        try {

            audioManager
                .clearCommunicationDevice()

        } catch (
            e: Throwable
        ) {

            Logger.w(
                "clearCommunicationDevice failed: " +
                    e.message
            )
        }

        try {

            audioManager.mode =
                AudioManager.MODE_NORMAL

        } catch (
            _: Throwable
        ) {
        }

        /*
         * Shared state updates HomeScreen too.
         */

        _routingState.value =
            RoutingState.Idle

        Logger.i(
            "✓ BLE communication routing released"
        )
    }

    /*
     * ============================================================
     * DEVICE QUERIES
     * ============================================================
     */

    fun getAvailableCommunicationDevices():
        List<AudioDeviceInfo> {

        return audioManager
            .availableCommunicationDevices
    }

    fun findFirstBluetoothCommunicationDevice():
        AudioDeviceInfo? {

        /*
         * STRICT BLE.
         *
         * Never silently select HFP/SCO here.
         */

        return audioManager
            .availableCommunicationDevices
            .firstOrNull {

                it.type ==
                    AudioDeviceInfo
                        .TYPE_BLE_HEADSET
            }
    }

    fun isBluetoothRouted():
        Boolean {

        val target =
            currentRoutedDevice
                ?: return false

        val actual =
            try {

                audioManager
                    .communicationDevice

            } catch (
                _: Throwable
            ) {

                null
            }
                ?: return false

        return _routingState.value
            is RoutingState.Active &&
            sameBleDevice(
                actual,
                target
            )
    }

    /*
     * ============================================================
     * RECOVERY SCHEDULING
     * ============================================================
     */

    private fun scheduleRestore(
        delayMs: Long =
            RESTORE_DEBOUNCE_MS
    ) {

        if (
            currentRoutedDevice ==
            null
        ) {

            return
        }

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
     * FIND BLE VERSION OF REQUESTED DEVICE
     * ============================================================
     */

    private fun findMatchingBleDevice(
        original:
            AudioDeviceInfo
    ): AudioDeviceInfo? {

        val devices =
            audioManager
                .availableCommunicationDevices
                .filter {

                    it.type ==
                        AudioDeviceInfo
                            .TYPE_BLE_HEADSET
                }

        if (
            original.type ==
            AudioDeviceInfo
                .TYPE_BLE_HEADSET
        ) {

            devices
                .firstOrNull {

                    it.id ==
                        original.id
                }
                ?.let {

                    return it
                }
        }

        if (
            original.address
                .isNotBlank()
        ) {

            devices
                .firstOrNull {

                    it.address ==
                        original.address
                }
                ?.let {

                    return it
                }
        }

        val name =
            original
                .productName
                ?.toString()

        if (
            !name
                .isNullOrBlank()
        ) {

            devices
                .firstOrNull {

                    it.productName
                        ?.toString() ==
                        name
                }
                ?.let {

                    return it
                }
        }

        return if (
            devices.size ==
            1
        ) {

            devices.first()

        } else {

            null
        }
    }

    /*
     * ============================================================
     * RESOLVE REMEMBERED ENDPOINT
     * ============================================================
     */

    private fun resolveBleTargetDevice(
        remembered:
            AudioDeviceInfo
    ): AudioDeviceInfo? {

        val devices =
            audioManager
                .availableCommunicationDevices
                .filter {

                    it.type ==
                        AudioDeviceInfo
                            .TYPE_BLE_HEADSET
                }

        devices
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

            devices
                .firstOrNull {

                    it.address ==
                        remembered.address
                }
                ?.let {

                    return it
                }
        }

        val name =
            remembered
                .productName
                ?.toString()

        if (
            !name
                .isNullOrBlank()
        ) {

            devices
                .firstOrNull {

                    it.productName
                        ?.toString() ==
                        name
                }
                ?.let {

                    return it
                }
        }

        return if (
            devices.size ==
            1
        ) {

            devices.first()

        } else {

            null
        }
    }

    /*
     * ============================================================
     * SAME BLE HEADSET
     * ============================================================
     */

    private fun sameBleDevice(
        first:
            AudioDeviceInfo,
        second:
            AudioDeviceInfo
    ): Boolean {

        if (
            first.type !=
            AudioDeviceInfo.TYPE_BLE_HEADSET ||

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

        return !firstName
            .isNullOrBlank() &&
            firstName ==
            secondName
    }

    /*
     * ============================================================
     * DEVICE LIST
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
                        AudioDeviceInfo
                            .TYPE_BLE_HEADSET ||

                        it.type ==
                        AudioDeviceInfo
                            .TYPE_BLUETOOTH_SCO ||

                        it.type ==
                        AudioDeviceInfo
                            .TYPE_BLUETOOTH_A2DP
                }
                .sortedBy {

                    when (
                        it.type
                    ) {

                        AudioDeviceInfo
                            .TYPE_BLE_HEADSET ->
                            0

                        AudioDeviceInfo
                            .TYPE_BLUETOOTH_SCO ->
                            1

                        AudioDeviceInfo
                            .TYPE_BLUETOOTH_A2DP ->
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

        /*
         * Don't trigger unnecessary Compose redraws.
         */

        val oldSummary =
            _availableDevices
                .value
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
     * DISPLAY
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
     * CONSTANTS + SHARED STATE
     * ============================================================
     */

    companion object {

        /*
         * ONE process-wide routing status.
         */

        private val sharedRoutingState =
            MutableStateFlow<RoutingState>(
                RoutingState.Idle
            )

        /*
         * Fast event-driven route recovery.
         */

        private const val
            RESTORE_DEBOUNCE_MS =
            150L

        private const val
            RESTORE_COOLDOWN_MS =
            1000L

        /*
         * Don't interpret a temporary Samsung LE endpoint
         * recreation as a true disconnect.
         */

        private const val
            DISCONNECT_CONFIRM_MS =
            6000L

        fun deviceTypeToString(
            type: Int
        ): String {

            return when (
                type
            ) {

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
