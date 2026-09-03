package com.btmicfix.ui.screens

import android.media.AudioDeviceInfo
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.audio.AudioRoutingManager.RoutingState
import com.btmicfix.bluetooth.LeAudioCacheRefresher
import com.btmicfix.bluetooth.LeGattScanner
import com.btmicfix.shizuku.LeAudioShizukuBridge
import com.btmicfix.shizuku.ShizukuManager
import com.btmicfix.ui.components.DeviceSelector
import com.btmicfix.ui.components.ShizukuStatusCard
import com.btmicfix.ui.components.VoiceAccessAutomationStatusCard
import com.btmicfix.ui.components.StatusCard
import com.btmicfix.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lightweight BTMicFix LE Audio screen.
 *
 * Important performance changes:
 *
 * - Giant UUID dumps are collapsed by default.
 * - Successful setup does not keep repainting huge diagnostic text.
 * - "Connect Buds with LE Audio" no longer runs the full live GATT
 *   scan every time.
 *
 * We already proved the Buds expose:
 *
 * ASCS 0x184E = YES
 * PACS 0x1850 = YES
 * BASS 0x184F = YES
 *
 * Therefore normal connection attempts can go:
 *
 * cache check/refresh
 *      ↓
 * LE Audio connect
 *      ↓
 * BLE communication route
 */
@OptIn(
    ExperimentalMaterial3Api::class
)
@Composable
fun HomeScreen(
    audioRoutingManager: AudioRoutingManager,
    shizukuManager: ShizukuManager,
    onSetupClick: () -> Unit,
    modifier: Modifier = Modifier
) {

    val routingState by
        audioRoutingManager
            .routingState
            .collectAsState()

    val availableDevices by
        audioRoutingManager
            .availableDevices
            .collectAsState()

    val context =
        LocalContext
            .current
            .applicationContext

    val coroutineScope =
        rememberCoroutineScope()

    /*
     * ============================================================
     * LIVE SCAN STATE
     * ============================================================
     */

    var scanning by
        remember {

            mutableStateOf(
                false
            )
        }

    var scanAscs by
        remember {

            mutableStateOf<Boolean?>(
                null
            )
        }

    var scanPacs by
        remember {

            mutableStateOf<Boolean?>(
                null
            )
        }

    var scanDetails by
        remember {

            mutableStateOf<String?>(
                null
            )
        }

    var showScanDetails by
        remember {

            mutableStateOf(
                false
            )
        }

    /*
     * ============================================================
     * CONNECT STATE
     * ============================================================
     */

    var connecting by
        remember {

            mutableStateOf(
                false
            )
        }

    var connectSummary by
        remember {

            mutableStateOf<String?>(
                null
            )
        }

    var connectDetails by
        remember {

            mutableStateOf<String?>(
                null
            )
        }

    var showConnectDetails by
        remember {

            mutableStateOf(
                false
            )
        }

    Scaffold(
        modifier =
            modifier,

        topBar = {

            TopAppBar(
                title = {

                    Text(
                        text =
                            "BTMicFix",

                        fontWeight =
                            FontWeight.Bold
                    )
                },

                colors =
                    TopAppBarDefaults
                        .topAppBarColors(
                            containerColor =
                                SurfaceDark,

                            titleContentColor =
                                Purple80
                        ),

                actions = {

                    IconButton(
                        onClick =
                            onSetupClick
                    ) {

                        Icon(
                            imageVector =
                                Icons.Default.Settings,

                            contentDescription =
                                "Setup",

                            tint =
                                Purple80
                        )
                    }
                }
            )
        },

        containerColor =
            SurfaceDark
    ) { innerPadding ->

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        innerPadding
                    )
                    .padding(
                        horizontal =
                            16.dp
                    )
                    .verticalScroll(
                        rememberScrollState()
                    ),

            verticalArrangement =
                Arrangement.spacedBy(
                    16.dp
                )
        ) {

            Spacer(
                modifier =
                    Modifier.height(
                        8.dp
                    )
            )

            /*
             * ====================================================
             * ROUTING STATUS
             * ====================================================
             */

            StatusCard(
                routingState =
                    routingState
            )

            RoutingControlButton(
                routingState =
                    routingState,

                onEnableRouting = {

                    audioRoutingManager
                        .routeToFirstAvailableBluetooth()
                },

                onDisableRouting = {

                    audioRoutingManager
                        .clearRouting()
                },

                onRetry = {

                    audioRoutingManager
                        .routeToFirstAvailableBluetooth()
                }
            )

            /*
             * ====================================================
             * DEVICES
             * ====================================================
             */

            DeviceSelector(
                devices =
                    availableDevices,

                onDeviceSelected = {
                        device ->

                    audioRoutingManager
                        .routeToBluetooth(
                            device.deviceInfo
                        )
                }
            )

            /*
             * ====================================================
             * SHIZUKU
             * ====================================================
             */

            ShizukuStatusCard(
                shizukuManager =
                    shizukuManager
            )

            VoiceAccessAutomationStatusCard()

            /*
             * ====================================================
             * CONNECT CARD
             * ====================================================
             *
             * Put the useful button first now.
             */

            LightweightConnectCard(
                working =
                    connecting,

                summary =
                    connectSummary,

                details =
                    connectDetails,

                showDetails =
                    showConnectDetails,

                onToggleDetails = {

                    showConnectDetails =
                        !showConnectDetails
                },

                onConnect = connect@{

                    if (
                        !shizukuManager
                            .isAvailable()
                    ) {

                        connectSummary =
                            "Shizuku is not ready."

                        connectDetails =
                            """
                            Open Shizuku and make sure
                            it says Running, then try again.
                            """.trimIndent()

                        return@connect
                    }

                    val buds =
                        findBudsAudioDevice(
                            availableDevices
                        )

                    if (
                        buds ==
                        null
                    ) {

                        connectSummary =
                            "No Buds headset found."

                        connectDetails =
                            """
                            Connect Antonio's Buds4 Pro normally
                            in Bluetooth settings first.
                            """.trimIndent()

                        return@connect
                    }

                    val preferredName =
                        buds.name

                    connecting =
                        true

                    showConnectDetails =
                        false

                    connectSummary =
                        "Checking Android LE Audio cache…"

                    connectDetails =
                        null

                    coroutineScope.launch {

                        /*
                         * ==========================================
                         * STEP 1
                         * CACHE CHECK / REFRESH
                         * ==========================================
                         *
                         * If 184E is already cached, this returns
                         * immediately.
                         *
                         * We no longer perform another full live
                         * GATT scan on every connection.
                         */

                        val refresh =
                            withContext(
                                Dispatchers.IO
                            ) {

                                LeAudioCacheRefresher
                                    .refresh(
                                        context =
                                            context,

                                        preferredDeviceName =
                                            preferredName
                                    )
                            }

                        if (
                            !refresh.cacheUpdated ||
                            !refresh.hasAscs
                        ) {

                            connectSummary =
                                "Android LE Audio cache is not ready."

                            connectDetails =
                                refresh.text

                            showConnectDetails =
                                true

                            connecting =
                                false

                            return@launch
                        }

                        /*
                         * ==========================================
                         * STEP 2
                         * LE AUDIO PROFILE
                         * ==========================================
                         */

                        connectSummary =
                            "Connecting Buds through LE Audio…"

                        val connectResult =
                            withContext(
                                Dispatchers.IO
                            ) {

                                LeAudioShizukuBridge
                                    .forceLeAudio(
                                        context =
                                            context,

                                        preferredDeviceName =
                                            preferredName
                                    )
                            }

                        val connected =
                            connectResult.contains(
                                "ACCEPTED - LE AUDIO CONNECTED"
                            ) ||
                                connectResult.contains(
                                    "ACCEPTED - ALREADY CONNECTED"
                                )

                        if (!connected) {

                            connectSummary =
                                "LE Audio did not finish connecting."

                            connectDetails =
                                refresh.text +
                                    "\n\n" +
                                    connectResult

                            showConnectDetails =
                                true

                            connecting =
                                false

                            return@launch
                        }

                        /*
                         * ==========================================
                         * STEP 3
                         * BLE COMMUNICATION DEVICE
                         * ==========================================
                         */

                        connectSummary =
                            "LE Audio connected. Activating Buds mic…"

                        /*
                         * Small settling delay only.
                         */

                        delay(
                            750
                        )

                        val routing =
                            audioRoutingManager
                                .routeToFirstAvailableBluetooth()

                        when (
                            routing
                        ) {

                            is RoutingState.Active -> {

                                connectSummary =
                                    "✓ LE Audio + Buds microphone active"

                                /*
                                 * Keep details stored but DO NOT render
                                 * the enormous diagnostic text unless
                                 * the user explicitly asks for it.
                                 */

                                connectDetails =
                                    refresh.text +
                                        "\n\n" +
                                        connectResult +
                                        "\n\n" +
                                        "FINAL ROUTING:\n" +
                                        routing.toString()

                                showConnectDetails =
                                    false
                            }

                            is RoutingState.Failed -> {

                                connectSummary =
                                    "LE Audio connected, but routing failed."

                                connectDetails =
                                    refresh.text +
                                        "\n\n" +
                                        connectResult +
                                        "\n\n" +
                                        routing.reason

                                showConnectDetails =
                                    true
                            }

                            else -> {

                                connectSummary =
                                    "LE Audio connection finished."

                                connectDetails =
                                    connectResult
                            }
                        }

                        connecting =
                            false
                    }
                }
            )

            /*
             * ====================================================
             * OPTIONAL DIAGNOSTIC SCAN
             * ====================================================
             */

            CompactGattScanCard(
                scanning =
                    scanning,

                ascs =
                    scanAscs,

                pacs =
                    scanPacs,

                details =
                    scanDetails,

                showDetails =
                    showScanDetails,

                onToggleDetails = {

                    showScanDetails =
                        !showScanDetails
                },

                onScan = scan@{

                    val buds =
                        findBudsAudioDevice(
                            availableDevices
                        )

                    if (
                        buds ==
                        null
                    ) {

                        scanDetails =
                            "Connect the Buds first."

                        showScanDetails =
                            true

                        return@scan
                    }

                    scanning =
                        true

                    scanAscs =
                        null

                    scanPacs =
                        null

                    showScanDetails =
                        false

                    coroutineScope.launch {

                        val result =
                            withContext(
                                Dispatchers.IO
                            ) {

                                LeGattScanner.scan(
                                    context =
                                        context,

                                    preferredDeviceName =
                                        buds.name
                                )
                            }

                        scanAscs =
                            result.hasAscs

                        scanPacs =
                            result.hasPacs

                        scanDetails =
                            result.text

                        scanning =
                            false
                    }
                }
            )

            /*
             * ====================================================
             * PERFORMANCE NOTE
             * ====================================================
             */

            PerformanceCard()

            Spacer(
                modifier =
                    Modifier.height(
                        24.dp
                    )
            )
        }
    }
}

/*
 * ================================================================
 * FIND BUDS
 * ================================================================
 */

private fun findBudsAudioDevice(
    devices:
        List<AudioRoutingManager.BluetoothAudioDevice>
): AudioRoutingManager.BluetoothAudioDevice? {

    /*
     * If LE already exists, use it.
     */

    devices
        .firstOrNull {

            it.deviceInfo.type ==
                AudioDeviceInfo
                    .TYPE_BLE_HEADSET
        }
        ?.let {

            return it
        }

    /*
     * Otherwise use HFP only to identify the physical Buds
     * for the cache / LE profile connection process.
     */

    devices
        .firstOrNull {

            it.deviceInfo.type ==
                AudioDeviceInfo
                    .TYPE_BLUETOOTH_SCO
        }
        ?.let {

            return it
        }

    return devices
        .firstOrNull()
}

/*
 * ================================================================
 * LIGHTWEIGHT CONNECT CARD
 * ================================================================
 */

@Composable
private fun LightweightConnectCard(
    working: Boolean,
    summary: String?,
    details: String?,
    showDetails: Boolean,
    onToggleDetails: () -> Unit,
    onConnect: () -> Unit
) {

    Card(
        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(
                16.dp
            ),

        colors =
            CardDefaults.cardColors(
                containerColor =
                    SurfaceCard
            )
    ) {

        Column(
            modifier =
                Modifier.padding(
                    16.dp
                ),

            verticalArrangement =
                Arrangement.spacedBy(
                    12.dp
                )
        ) {

            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Icon(
                    imageVector =
                        Icons.Default
                            .BluetoothAudio,

                    contentDescription =
                        null,

                    tint =
                        Purple80
                )

                Spacer(
                    modifier =
                        Modifier.width(
                            8.dp
                        )
                )

                Text(
                    text =
                        "LE Audio + Buds Mic",

                    style =
                        MaterialTheme
                            .typography
                            .titleMedium,

                    fontWeight =
                        FontWeight.Bold
                )
            }

            Text(
                text =
                    "Uses the LE Audio connection we successfully " +
                        "proved, without constantly polling Android.",

                style =
                    MaterialTheme
                        .typography
                        .bodySmall,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )

            Button(
                onClick =
                    onConnect,

                enabled =
                    !working,

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            56.dp
                        ),

                shape =
                    RoundedCornerShape(
                        16.dp
                    ),

                colors =
                    ButtonDefaults.buttonColors(
                        containerColor =
                            Purple40
                    )
            ) {

                if (working) {

                    CircularProgressIndicator(
                        modifier =
                            Modifier.size(
                                20.dp
                            ),

                        strokeWidth =
                            2.dp
                    )

                } else {

                    Icon(
                        imageVector =
                            Icons.Default
                                .BluetoothAudio,

                        contentDescription =
                            null
                    )
                }

                Spacer(
                    modifier =
                        Modifier.width(
                            8.dp
                        )
                )

                Text(
                    if (working) {
                        "Connecting…"
                    } else {
                        "Connect Buds with LE Audio"
                    }
                )
            }

            if (
                summary !=
                null
            ) {

                HorizontalDivider()

                Text(
                    text =
                        summary,

                    fontWeight =
                        FontWeight.Bold
                )
            }

            if (
                details !=
                null
            ) {

                TextButton(
                    onClick =
                        onToggleDetails
                ) {

                    Text(
                        if (showDetails) {
                            "Hide technical details"
                        } else {
                            "Show technical details"
                        }
                    )
                }

                /*
                 * The large diagnostics are NOT composed unless
                 * explicitly expanded.
                 */

                if (
                    showDetails
                ) {

                    Text(
                        text =
                            details,

                        style =
                            MaterialTheme
                                .typography
                                .bodySmall
                    )
                }
            }
        }
    }
}

/*
 * ================================================================
 * COMPACT GATT SCAN
 * ================================================================
 */

@Composable
private fun CompactGattScanCard(
    scanning: Boolean,
    ascs: Boolean?,
    pacs: Boolean?,
    details: String?,
    showDetails: Boolean,
    onToggleDetails: () -> Unit,
    onScan: () -> Unit
) {

    Card(
        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(
                16.dp
            ),

        colors =
            CardDefaults.cardColors(
                containerColor =
                    SurfaceCard
            )
    ) {

        Column(
            modifier =
                Modifier.padding(
                    16.dp
                ),

            verticalArrangement =
                Arrangement.spacedBy(
                    10.dp
                )
        ) {

            Text(
                text =
                    "Optional LE diagnostic",

                style =
                    MaterialTheme
                        .typography
                        .titleMedium,

                fontWeight =
                    FontWeight.Bold
            )

            if (
                ascs !=
                null
            ) {

                Text(
                    text =
                        "ASCS 0x184E: " +
                            yesNo(
                                ascs
                            )
                )
            }

            if (
                pacs !=
                null
            ) {

                Text(
                    text =
                        "PACS 0x1850: " +
                            yesNo(
                                pacs
                            )
                )
            }

            OutlinedButton(
                onClick =
                    onScan,

                enabled =
                    !scanning,

                modifier =
                    Modifier.fillMaxWidth()
            ) {

                Icon(
                    imageVector =
                        Icons.Default.Search,

                    contentDescription =
                        null
                )

                Spacer(
                    modifier =
                        Modifier.width(
                            8.dp
                        )
                )

                Text(
                    if (scanning) {
                        "Scanning…"
                    } else {
                        "Run LE Service Scan"
                    }
                )
            }

            if (
                details !=
                null
            ) {

                TextButton(
                    onClick =
                        onToggleDetails
                ) {

                    Text(
                        if (showDetails) {
                            "Hide scan details"
                        } else {
                            "Show scan details"
                        }
                    )
                }

                if (
                    showDetails
                ) {

                    Text(
                        text =
                            details,

                        style =
                            MaterialTheme
                                .typography
                                .bodySmall
                    )
                }
            }
        }
    }
}

/*
 * ================================================================
 * ROUTING CONTROL
 * ================================================================
 */

@Composable
private fun RoutingControlButton(
    routingState: RoutingState,
    onEnableRouting: () -> Unit,
    onDisableRouting: () -> Unit,
    onRetry: () -> Unit
) {

    when (
        routingState
    ) {

        is RoutingState.Idle -> {

            Button(
                onClick =
                    onEnableRouting,

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            56.dp
                        ),

                colors =
                    ButtonDefaults.buttonColors(
                        containerColor =
                            Purple40
                    )
            ) {

                Icon(
                    imageVector =
                        Icons.Default
                            .PowerSettingsNew,

                    contentDescription =
                        null
                )

                Spacer(
                    modifier =
                        Modifier.width(
                            8.dp
                        )
                )

                Text(
                    "Enable BLE Routing"
                )
            }
        }

        is RoutingState.Routing -> {

            Button(
                onClick = {
                },

                enabled =
                    false,

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            56.dp
                        )
            ) {

                CircularProgressIndicator(
                    modifier =
                        Modifier.size(
                            20.dp
                        ),

                    strokeWidth =
                        2.dp
                )

                Spacer(
                    modifier =
                        Modifier.width(
                            8.dp
                        )
                )

                Text(
                    "Routing…"
                )
            }
        }

        is RoutingState.Active -> {

            OutlinedButton(
                onClick =
                    onDisableRouting,

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            56.dp
                        )
            ) {

                Icon(
                    imageVector =
                        Icons.Default
                            .PowerSettingsNew,

                    contentDescription =
                        null
                )

                Spacer(
                    modifier =
                        Modifier.width(
                            8.dp
                        )
                )

                Text(
                    "Disable Routing"
                )
            }
        }

        is RoutingState.Failed -> {

            Button(
                onClick =
                    onRetry,

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            56.dp
                        ),

                colors =
                    ButtonDefaults.buttonColors(
                        containerColor =
                            StatusFailed.copy(
                                alpha =
                                    0.8f
                            )
                    )
            ) {

                Icon(
                    imageVector =
                        Icons.Default.Refresh,

                    contentDescription =
                        null
                )

                Spacer(
                    modifier =
                        Modifier.width(
                            8.dp
                        )
                )

                Text(
                    "Retry BLE Route"
                )
            }
        }
    }
}

/*
 * ================================================================
 * PERFORMANCE INFO
 * ================================================================
 */

@Composable
private fun PerformanceCard() {

    Card(
        modifier =
            Modifier.fillMaxWidth(),

        colors =
            CardDefaults.cardColors(
                containerColor =
                    SurfaceCard
            )
    ) {

        Column(
            modifier =
                Modifier.padding(
                    16.dp
                ),

            verticalArrangement =
                Arrangement.spacedBy(
                    8.dp
                )
        ) {

            Text(
                text =
                    "Performance mode",

                style =
                    MaterialTheme
                        .typography
                        .titleMedium,

                fontWeight =
                    FontWeight.Bold
            )

            Text(
                text =
                    "The old 250 ms routing watchdog is removed.\n\n" +
                        "BTMicFix now waits for Android to report an " +
                        "actual communication-device change before " +
                        "attempting one debounced recovery.\n\n" +
                        "Large Bluetooth diagnostic dumps stay collapsed " +
                        "unless you open them.",

                style =
                    MaterialTheme
                        .typography
                        .bodySmall,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )
        }
    }
}

/*
 * ================================================================
 * YES / NO
 * ================================================================
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
