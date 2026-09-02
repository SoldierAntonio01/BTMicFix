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
 * BTMicFix low-overhead BLE Audio routing manager.
 *
 * IMPORTANT CHANGE:
 *
 * OLD VERSION:
 *
 * - checked routing every 250 ms
 * - repeatedly queried AudioManager
 * - could repeatedly call setCommunicationDevice()
 *
 * NEW VERSION:
 *
 * - NO continuous polling loop
 * - listens for actual communication-device changes
 * - only attempts recovery when Android really changes the route
 * - recovery is delayed/debounced
 * - recovery has a cooldown
 *
 * This is much easier on:
 *
 * - AudioService
 * - Bluetooth stack
 * - system_server
 * - the main UI
 *
 * while still protecting the Buds microphone route.
 */
class AudioRoutingManager(
    private val context: Context
) {

    private val audioManager: AudioManager =
        context.getSystemService<AudioManager>()
            ?: throw IllegalStateException(
                "AudioManager not available"
            )

    /*
     * ============================================================
     * STATE
     * ============================================================
     */

    private val _routingState =
        MutableStateFlow<RoutingState>(
            RoutingState.Idle
        )

    val routingState:
        StateFlow<RoutingState> =
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

    private var monitoring =
        false

    /*
     * ============================================================
     * DEBOUNCED RECOVERY
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
     * ROUTE RESTORE RUNNABLE
     * ============================================================
     *
     * Runs ONLY when Android tells us the communication device
     * actually changed away from the Buds.
     *
     * There is no repeating watchdog.
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
                    "BLE route recovery skipped: " +
                        "BLE headset endpoint unavailable"
                )

                scheduleDisconnectCheck()

                return@Runnable
            }

            /*
             * Android may recreate AudioDeviceInfo objects.
             * Keep our remembered target fresh.
             */

            currentRoutedDevice =
                target

            val actual =
                try {

                    audioManager
                        .communicationDevice

                } catch (_: Throwable) {

                    null
                }

            /*
             * Route already recovered on its own.
             *
             * Do NOTHING.
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
                    "BLE communication route recovered by Android"
                )

                return@Runnable
            }

            /*
             * Prevent rapid repeated routing calls.
             */

            val now =
                SystemClock.elapsedRealtime()

            val sinceLastRestore =
                now -
                    lastRestoreAttemptMs

            if (
                sinceLastRestore <
                RESTORE_COOLDOWN_MS
            ) {

                val remaining =
                    RESTORE_COOLDOWN_MS -
                        sinceLastRestore

                scheduleRestore(
                    delayMs =
                        remaining
                )

                return@Runnable
            }

            lastRestoreAttemptMs =
                now

            /*
             * Only now do we touch AudioManager.
             */

            try {

                if (
                    audioManager.mode !=
                    AudioManager
                        .MODE_IN_COMMUNICATION
                ) {

                    audioManager.mode =
                        AudioManager
                            .MODE_IN_COMMUNICATION
                }

                Logger.i(
                    "Communication route changed away " +
                        "from BLE. Performing one recovery attempt."
                )

                val success =
                    audioManager
                        .setCommunicationDevice(
                            target
                        )

                if (success) {

                    _routingState.value =
                        RoutingState.Active(
                            buildDisplayName(
                                target
                            )
                        )

                    Logger.i(
                        "✓ BLE route recovery request accepted"
                    )

                } else {

                    Logger.w(
                        "BLE route recovery request returned false"
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
     * LE Audio can briefly recreate its endpoint during transport
     * changes.
     *
     * Do NOT immediately clear routing just because an AudioDeviceInfo
     * object disappeared for a moment.
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

                Logger.i(
                    "BLE endpoint returned after temporary change"
                )

                scheduleRestore(
                    RESTORE_DEBOUNCE_MS
                )

                return@Runnable
            }

            /*
             * Still genuinely gone after the confirmation delay.
             */

            Logger.w(
                "BLE headset endpoint is still gone. " +
                    "Releasing communication routing."
            )

            clearRouting()
        }

    /*
     * ============================================================
     * COMMUNICATION DEVICE LISTENER
     * ============================================================
     *
     * Android directly tells us whenever the communication device
     * changes.
     *
     * This replaces the 250 ms watchdog.
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
         * Android is still using our BLE headset.
         *
         * Cancel any pending restore.
         */

        if (
            device != null &&
            sameBleDevice(
                device,
                target
            )
        ) {

            cancelRestore()

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
         * Android actually changed away from our route.
         *
         * Do NOT immediately fight it.
         *
         * Wait briefly. This filters temporary Samsung route changes.
         */

        Logger.i(
            "Communication device changed: " +
                "${device?.productName} " +
                "[${device?.let {
                    deviceTypeToString(
                        it.type
                    )
                } ?: "NONE"}]"
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
                 * If we're supposed to be routed but Android just
                 * recreated the endpoint, perform one delayed check.
                 */

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

                val remembered =
                    currentRoutedDevice
                        ?: return

                val rememberedRemoved =
                    removedDevices.any {
                        removed ->

                        removed.id ==
                            remembered.id
                    }

                if (
                    rememberedRemoved
                ) {

                    Logger.i(
                        "Current BLE AudioDeviceInfo disappeared. " +
                            "Waiting before treating it as a disconnect."
                    )

                    scheduleDisconnectCheck()
                }
            }
        }

    /*
     * ============================================================
     * ROUTING STATES
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
            "Starting low-overhead audio monitoring"
        )

        try {

            audioManager
                .registerAudioDeviceCallback(
                    deviceCallback,
                    null
                )

        } catch (e: Throwable) {

            Logger.w(
                "Audio device callback registration failed: " +
                    "${e.message}"
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
                "Communication-device listener registration failed",
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

        Logger.i(
            "Stopping audio monitoring"
        )

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
    }

    /*
     * ============================================================
     * ROUTE TO BLUETOOTH
     * ============================================================
     *
     * This version is STRICT BLE.
     *
     * It will NOT silently fall back to HFP/SCO.
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

            Logger.w(
                "Strict BLE routing: TYPE_BLE_HEADSET unavailable"
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
             * Set communication mode ONCE.
             *
             * We no longer rewrite it four times per second.
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
                    "✓ BLE routing active: $displayName"
                )

                return state
            }

            /*
             * Failed.
             */

            currentRoutedDevice =
                null

            audioManager.mode =
                AudioManager.MODE_NORMAL

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
     * ROUTE TO FIRST BLE HEADSET
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
     * ROUTE BY ADDRESS
     * ============================================================
     */

    fun routeToDeviceByAddress(
        address: String
    ): RoutingState {

        val devices =
            audioManager
                .availableCommunicationDevices

        val exactBle =
            devices
                .firstOrNull {

                    it.type ==
                        AudioDeviceInfo
                            .TYPE_BLE_HEADSET &&

                        it.address ==
                            address
                }

        if (
            exactBle !=
            null
        ) {

            return routeToBluetooth(
                exactBle
            )
        }

        /*
         * Samsung may mask or recreate endpoint addresses.
         *
         * If exactly one BLE headset exists, it is safer and more
         * useful to use that than to fall back to HFP.
         */

        val bleDevices =
            devices.filter {

                it.type ==
                    AudioDeviceInfo
                        .TYPE_BLE_HEADSET
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
            "BLE address ${
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
         * Set this first so the communication-device callback
         * caused by clearCommunicationDevice() will NOT try to
         * restore the route.
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
                "clearCommunicationDevice failed: " +
                    "${e.message}"
            )
        }

        try {

            audioManager.mode =
                AudioManager.MODE_NORMAL

        } catch (_: Throwable) {
        }

        _routingState.value =
            RoutingState.Idle

        Logger.i(
            "Communication routing released"
        )
    }

    /*
     * ============================================================
     * GET AVAILABLE COMMUNICATION DEVICES
     * ============================================================
     */

    fun getAvailableCommunicationDevices():
        List<AudioDeviceInfo> {

        return audioManager
            .availableCommunicationDevices
    }

    /*
     * ============================================================
     * FIND FIRST BLE HEADSET
     * ============================================================
     */

    fun findFirstBluetoothCommunicationDevice():
        AudioDeviceInfo? {

        return audioManager
            .availableCommunicationDevices
            .firstOrNull {

                it.type ==
                    AudioDeviceInfo
                        .TYPE_BLE_HEADSET
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
            try {

                audioManager
                    .communicationDevice

            } catch (_: Throwable) {

                null
            }
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
     * SCHEDULE ONE RESTORE
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

    /*
     * ============================================================
     * DISCONNECT CHECK
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

        val devices =
            audioManager
                .availableCommunicationDevices
                .filter {

                    it.type ==
                        AudioDeviceInfo
                            .TYPE_BLE_HEADSET
                }

        /*
         * Already BLE and still present.
         */

        if (
            original.type ==
            AudioDeviceInfo.TYPE_BLE_HEADSET
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

        /*
         * Match by address when Android exposes one.
         */

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

        /*
         * Match product name.
         */

        val originalName =
            original
                .productName
                ?.toString()

        if (
            !originalName
                .isNullOrBlank()
        ) {

            devices
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
     * RESOLVE REMEMBERED BLE ENDPOINT
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

        val rememberedName =
            remembered
                .productName
                ?.toString()

        if (
            !rememberedName
                .isNullOrBlank()
        ) {

            devices
                .firstOrNull {

                    it.productName
                        ?.toString() ==
                        rememberedName
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
     * SAME BLE DEVICE
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
            AudioDeviceInfo
                .TYPE_BLE_HEADSET ||

            second.type !=
            AudioDeviceInfo
                .TYPE_BLE_HEADSET
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
                /*
                 * Put BLE first.
                 */
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
         * Only update StateFlow when the visible device information
         * actually changed.
         *
         * This reduces unnecessary Compose recompositions.
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
         * Old watchdog:
         *
         * 250 ms forever
         *
         * New system:
         *
         * NO polling.
         *
         * Android tells us when a route changes.
         */

        private const val
            RESTORE_DEBOUNCE_MS =
            1200L

        private const val
            RESTORE_COOLDOWN_MS =
            4000L

        private const val
            DISCONNECT_CONFIRM_MS =
            3000L

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
