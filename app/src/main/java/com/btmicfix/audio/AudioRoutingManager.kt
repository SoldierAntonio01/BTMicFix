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
 * GOAL:
 *
 * Keep the working:
 *
 * - Galaxy Buds microphone
 * - LE Audio
 * - normal-quality music
 * - long-distance Voice Access
 *
 * while reducing the occasional 2–3 second audio dropout.
 *
 *
 * IMPORTANT CHANGES:
 *
 * OLD:
 *
 * - 250 ms watchdog polling constantly
 * - later event-driven version waited 1200 ms to restore
 * - could rewrite MODE_IN_COMMUNICATION during recovery
 *
 * NEW:
 *
 * - NO permanent polling
 * - Android tells us when the communication device changes
 * - only 150 ms debounce before recovery
 * - first recovery only re-selects the BLE device
 * - MODE_IN_COMMUNICATION is touched only if soft recovery fails
 * - 1 second restore cooldown
 * - temporary BLE endpoint changes get 6 seconds before being
 *   treated as a real disconnect
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
     * ROUTING STATE
     * ============================================================
     */

    private val _routingState =
        MutableStateFlow<RoutingState>(
            RoutingState.Idle
        )

    val routingState:
        StateFlow<RoutingState> =
        _routingState.asStateFlow()

    /*
     * ============================================================
     * AVAILABLE BLUETOOTH DEVICES
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
     * CURRENT TARGET
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
     * ROUTE RESTORE
     * ============================================================
     *
     * Runs only after Android actually reports that the
     * communication route changed.
     *
     * There is NO repeating watchdog.
     */

    private val restoreRunnable =
        Runnable {

            restoreScheduled =
                false

            val remembered =
                currentRoutedDevice
                    ?: return@Runnable

            /*
             * Find the current AudioDeviceInfo representing
             * the same BLE headset.
             *
             * Android may destroy/recreate AudioDeviceInfo objects.
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
                    "BLE route recovery skipped: " +
                        "BLE headset endpoint unavailable"
                )

                /*
                 * Do not immediately clear everything.
                 *
                 * LE Audio can temporarily recreate its
                 * communication endpoint.
                 */

                scheduleDisconnectCheck()

                return@Runnable
            }

            currentRoutedDevice =
                target

            /*
             * Check the route Android is currently using.
             */

            val actual =
                try {

                    audioManager
                        .communicationDevice

                } catch (_: Throwable) {

                    null
                }

            /*
             * Android already fixed itself.
             *
             * Do absolutely nothing.
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
                    "BLE communication route already restored"
                )

                return@Runnable
            }

            /*
             * ====================================================
             * COOLDOWN
             * ====================================================
             *
             * Do not rapidly hammer AudioService if Samsung sends
             * several route callbacks close together.
             */

            val now =
                SystemClock.elapsedRealtime()

            val timeSinceLastAttempt =
                now -
                    lastRestoreAttemptMs

            if (
                timeSinceLastAttempt <
                RESTORE_COOLDOWN_MS
            ) {

                val remaining =
                    RESTORE_COOLDOWN_MS -
                        timeSinceLastAttempt

                scheduleRestore(
                    remaining
                )

                return@Runnable
            }

            lastRestoreAttemptMs =
                now

            /*
             * ====================================================
             * FAST SOFT RECOVERY
             * ====================================================
             *
             * FIRST:
             *
             * Just restore setCommunicationDevice().
             *
             * Do NOT immediately modify AudioManager.mode.
             *
             * Changing audio mode can trigger a larger audio-pipeline
             * reconfiguration and can create an audible gap.
             */

            try {

                Logger.i(
                    "Communication route changed away from BLE. " +
                        "Performing fast soft recovery."
                )

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
                 * Only if the soft route restore failed do we
                 * restore communication mode and try once again.
                 */

                if (!success) {

                    Logger.w(
                        "Soft BLE recovery failed. " +
                            "Trying communication-mode recovery."
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
     * LE Audio endpoints can disappear and reappear while Android
     * changes transport state.
     *
     * We now wait 6 seconds before declaring the Buds genuinely gone.
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

            /*
             * Endpoint came back.
             */

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
             * Still gone after the confirmation period.
             *
             * Now consider it a real disconnect.
             */

            Logger.w(
                "BLE headset endpoint still unavailable. " +
                    "Releasing communication routing."
            )

            clearRouting()
        }

    /*
     * ============================================================
     * COMMUNICATION DEVICE CHANGED LISTENER
     * ============================================================
     *
     * This replaces the old permanent watchdog.
     *
     * Android directly informs BTMicFix when the communication
     * route changes.
     */

    private val communicationDeviceChangedListener =
        AudioManager
            .OnCommunicationDeviceChangedListener {
                    device ->

                handleCommunicationDeviceChanged(
                    device
                )
            }

    /*
     * ============================================================
     * HANDLE COMMUNICATION ROUTE CHANGE
     * ============================================================
     */

    private fun handleCommunicationDeviceChanged(
        device: AudioDeviceInfo?
    ) {

        val target =
            currentRoutedDevice
                ?: return

        /*
         * The route is still our BLE headset.
         *
         * Cancel any recovery waiting in the queue.
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

            currentRoutedDevice =
                device

            _routingState.value =
                RoutingState.Active(
                    buildDisplayName(
                        device
                    )
                )

            return
        }

        /*
         * Android really changed away from our BLE communication route.
         *
         * Wait only 150 ms.
         *
         * This filters very short transient changes while avoiding
         * the old 1.2-second recovery delay.
         */

        Logger.i(
            "Communication route changed to: " +
                "${device?.productName ?: "NONE"} " +
                "[${
                    device
                        ?.let {
                            deviceTypeToString(
                                it.type
                            )
                        }
                        ?: "NONE"
                }]"
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

                Logger.d(
                    "Audio devices added: ${
                        addedDevices.map {
                            deviceTypeToString(
                                it.type
                            )
                        }
                    }"
                )

                refreshAvailableDevices()

                /*
                 * If routing is supposed to be active,
                 * Android may have just recreated the BLE endpoint.
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

                Logger.d(
                    "Audio devices removed: ${
                        removedDevices.map {
                            deviceTypeToString(
                                it.type
                            )
                        }
                    }"
                )

                refreshAvailableDevices()

                val remembered =
                    currentRoutedDevice
                        ?: return

                val targetWasRemoved =
                    removedDevices.any {
                        removed ->

                        removed.id ==
                            remembered.id
                    }

                if (
                    targetWasRemoved
                ) {

                    Logger.i(
                        "Current BLE AudioDeviceInfo disappeared. " +
                            "Waiting before declaring disconnect."
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

    /*
     * ============================================================
     * BLUETOOTH DEVICE MODEL
     * ============================================================
     */

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
            "Starting event-driven audio monitoring"
        )

        /*
         * Audio endpoint additions/removals.
         */

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

        /*
         * Communication route changes.
         */

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
     * STRICT LE AUDIO.
     *
     * HFP / SCO is NOT used as a fallback.
     */

    fun routeToBluetooth(
        requestedDevice:
            AudioDeviceInfo
    ): RoutingState {

        val target =
            findMatchingBleDevice(
                requestedDevice
            )

        /*
         * No BLE communication endpoint.
         */

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
                "Strict BLE routing failed: " +
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
             * ====================================================
             * INITIAL ROUTING
             * ====================================================
             *
             * Initial activation still uses communication mode.
             *
             * We only avoid repeatedly rewriting the mode during
             * ordinary recovery.
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
                    "✓ BLE communication route active: " +
                        displayName
                )

                return state
            }

            /*
             * Routing request failed.
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

            Logger.e(
                "setCommunicationDevice returned false"
            )

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
     * ROUTE TO FIRST AVAILABLE BLE HEADSET
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

            Logger.w(
                "TYPE_BLE_HEADSET not available"
            )

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
         * Exact BLE endpoint.
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
            exactBle !=
            null
        ) {

            return routeToBluetooth(
                exactBle
            )
        }

        /*
         * Samsung can recreate BLE endpoints with a changed/masked
         * address representation.
         *
         * If exactly one BLE headset exists, use it.
         */

        val bleDevices =
            devices
                .filter {

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

        /*
         * Stop any scheduled restoration FIRST.
         */

        cancelRestore()
        cancelDisconnectCheck()

        /*
         * Clear remembered target BEFORE asking Android to release
         * the communication device.
         *
         * Otherwise our communication-device callback could see
         * the change and immediately restore it again.
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
     * FIND FIRST BLE HEADSET
     * ============================================================
     */

    fun findFirstBluetoothCommunicationDevice():
        AudioDeviceInfo? {

        return audioManager
            .availableCommunicationDevices
            .firstOrNull {

                it.type ==
                    AudioDeviceInfo.TYPE_BLE_HEADSET
            }
    }

    /*
     * ============================================================
     * ROUTING CHECK
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
     * SCHEDULE ROUTE RESTORE
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

        /*
         * Don't stack many restores.
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

    /*
     * ============================================================
     * CANCEL ROUTE RESTORE
     * ============================================================
     */

    private fun cancelRestore() {

        restoreScheduled =
            false

        handler.removeCallbacks(
            restoreRunnable
        )
    }

    /*
     * ============================================================
     * SCHEDULE DISCONNECT CONFIRMATION
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

    /*
     * ============================================================
     * CANCEL DISCONNECT CONFIRMATION
     * ============================================================
     */

    private fun cancelDisconnectCheck() {

        disconnectCheckScheduled =
            false

        handler.removeCallbacks(
            disconnectCheckRunnable
        )
    }

    /*
     * ============================================================
     * MATCH REQUESTED AUDIO DEVICE TO BLE HEADSET
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
         * Already BLE.
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
         * Match by address.
         */

        if (
            original.address
                .isNotBlank()
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
         * Match by product name.
         */

        val originalName =
            original
                .productName
                ?.toString()

        if (
            !originalName
                .isNullOrBlank()
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
         * If only one BLE headset exists,
         * it is almost certainly our Buds.
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
     * RESOLVE REMEMBERED BLE TARGET
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
         * Same AudioDeviceInfo ID.
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
            remembered.address
                .isNotBlank()
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
            !rememberedName
                .isNullOrBlank()
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
     * SAME PHYSICAL BLE DEVICE
     * ============================================================
     */

    private fun sameBleDevice(
        first:
            AudioDeviceInfo,
        second:
            AudioDeviceInfo
    ): Boolean {

        /*
         * Both MUST be actual BLE headset endpoints.
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
         * Exact Android audio-device ID.
         */

        if (
            first.id ==
                second.id
        ) {

            return true
        }

        /*
         * Same address.
         */

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

        /*
         * Same product name.
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
            !firstName
                .isNullOrBlank() &&

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

                /*
                 * BLE first.
                 */

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
         * Avoid unnecessary StateFlow updates / Compose redraws.
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
         * FAST RECOVERY
         *
         * Old:
         * 1200 ms
         *
         * New:
         * 150 ms
         */

        private const val
            RESTORE_DEBOUNCE_MS =
            150L

        /*
         * Don't repeatedly issue route calls more frequently
         * than once per second.
         */

        private const val
            RESTORE_COOLDOWN_MS =
            1000L

        /*
         * Give Samsung plenty of time to recreate a temporary
         * BLE endpoint before treating it as a real disconnect.
         */

        private const val
            DISCONNECT_CONFIRM_MS =
            6000L

        /*
         * ========================================================
         * DEVICE TYPE LABELS
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
