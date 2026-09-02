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
import com.btmicfix.bluetooth.LeGattScanner
import com.btmicfix.shizuku.ShizukuManager
import com.btmicfix.ui.components.DeviceSelector
import com.btmicfix.ui.components.ShizukuStatusCard
import com.btmicfix.ui.components.StatusCard
import com.btmicfix.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * BTMicFix diagnostic screen.
 *
 * This build focuses on SAFE live LE GATT discovery.
 *
 * It does NOT use the experimental Force LE Audio Binder button.
 *
 * We first want to determine whether the Buds4 Pro actually expose
 * the core LE Audio GATT services to this Fold6.
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
     * GATT SCAN STATE
     * ============================================================
     */

    var scanning by
        remember {
            mutableStateOf(
                false
            )
        }

    var scanResult by
        remember {
            mutableStateOf<String?>(
                null
            )
        }

    var ascsFound by
        remember {
            mutableStateOf<Boolean?>(
                null
            )
        }

    var pacsFound by
        remember {
            mutableStateOf<Boolean?>(
                null
            )
        }

    /*
     * ============================================================
     * SCREEN
     * ============================================================
     */

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
             * ROUTING DIAGNOSTIC
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
             * BLUETOOTH DEVICES
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
             * SHIZUKU STATUS
             * ====================================================
             *
             * We keep the status card because BTMicFix still uses
             * Shizuku elsewhere.
             *
             * The LIVE GATT SCAN itself does NOT require the
             * privileged Shizuku Binder operation.
             */

            ShizukuStatusCard(
                shizukuManager =
                    shizukuManager
            )

            /*
             * ====================================================
             * SAFE LE GATT SCAN
             * ====================================================
             */

            LeGattScanCard(
                scanning =
                    scanning,

                result =
                    scanResult,

                ascsFound =
                    ascsFound,

                pacsFound =
                    pacsFound,

                onScan = {

                    /*
                     * Prefer HFP representation only to identify
                     * which headset the user currently has connected.
                     *
                     * LeGattScanner then finds the real bonded
                     * BluetoothDevice by name.
                     */

                    val budsDevice =
                        availableDevices
                            .firstOrNull {

                                it.deviceInfo.type ==
                                    AudioDeviceInfo
                                        .TYPE_BLUETOOTH_SCO
                            }
                            ?: availableDevices
                                .firstOrNull {

                                    it.deviceInfo.type ==
                                        AudioDeviceInfo
                                            .TYPE_BLE_HEADSET
                                }
                            ?: availableDevices
                                .firstOrNull()

                    if (
                        budsDevice ==
                        null
                    ) {

                        scanResult =
                            """
                            No Bluetooth headset found.

                            Connect Antonio's Buds4 Pro
                            first.
                            """.trimIndent()

                        return@LeGattScanCard
                    }

                    val preferredName =
                        budsDevice.name

                    scanning =
                        true

                    ascsFound =
                        null

                    pacsFound =
                        null

                    scanResult =
                        """
                        Connecting temporarily to the
                        Buds over Bluetooth LE GATT...

                        Device:
                        $preferredName

                        No HFP/A2DP profile changes
                        are being requested.
                        """.trimIndent()

                    coroutineScope.launch {

                        val result =
                            withContext(
                                Dispatchers.IO
                            ) {

                                LeGattScanner.scan(
                                    context =
                                        context,

                                    preferredDeviceName =
                                        preferredName
                                )
                            }

                        scanResult =
                            result.text

                        ascsFound =
                            result.hasAscs

                        pacsFound =
                            result.hasPacs

                        scanning =
                            false
                    }
                }
            )

            /*
             * ====================================================
             * CURRENT EXPERIMENT
             * ====================================================
             */

            CurrentExperimentCard()

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
 * LIVE LE GATT SCAN CARD
 * ================================================================
 */

@Composable
private fun LeGattScanCard(
    scanning: Boolean,
    result: String?,
    ascsFound: Boolean?,
    pacsFound: Boolean?,
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
            CardDefaults
                .cardColors(
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
                        Icons.Default.Search,

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
                        "Live Buds LE Service Scan",

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
                    "Safely connects to the Buds' GATT server " +
                        "over Bluetooth LE and checks the actual " +
                        "services being exposed right now.",

                style =
                    MaterialTheme
                        .typography
                        .bodySmall,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )

            /*
             * ----------------------------------------------------
             * QUICK RESULT BOX
             * ----------------------------------------------------
             */

            if (
                ascsFound != null ||
                pacsFound != null
            ) {

                HorizontalDivider()

                Text(
                    text =
                        "ASCS 0x184E: " +
                            when (ascsFound) {
                                true -> "YES"
                                false -> "NO"
                                null -> "UNKNOWN"
                            },

                    style =
                        MaterialTheme
                            .typography
                            .bodyMedium,

                    fontWeight =
                        FontWeight.Bold
                )

                Text(
                    text =
                        "PACS 0x1850: " +
                            when (pacsFound) {
                                true -> "YES"
                                false -> "NO"
                                null -> "UNKNOWN"
                            },

                    style =
                        MaterialTheme
                            .typography
                            .bodyMedium,

                    fontWeight =
                        FontWeight.Bold
                )
            }

            /*
             * ----------------------------------------------------
             * SCAN BUTTON
             * ----------------------------------------------------
             */

            Button(
                onClick =
                    onScan,

                enabled =
                    !scanning,

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
                    ButtonDefaults
                        .buttonColors(
                            containerColor =
                                Purple40
                        )
            ) {

                if (scanning) {

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
                    if (scanning) {

                        "Scanning LE Services…"

                    } else {

                        "Scan Buds LE Services"
                    }
                )
            }

            /*
             * ----------------------------------------------------
             * FULL RESULT
             * ----------------------------------------------------
             */

            if (
                result != null
            ) {

                HorizontalDivider()

                Text(
                    text =
                        result,

                    style =
                        MaterialTheme
                            .typography
                            .bodySmall
                )
            }
        }
    }
}

/*
 * ================================================================
 * NORMAL ROUTING CONTROL
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

                shape =
                    RoundedCornerShape(
                        16.dp
                    ),

                colors =
                    ButtonDefaults
                        .buttonColors(
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
                    "Enable Routing"
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
                    ButtonDefaults
                        .buttonColors(
                            containerColor =
                                StatusFailed
                                    .copy(
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
                    "Retry"
                )
            }
        }
    }
}

/*
 * ================================================================
 * CURRENT EXPERIMENT
 * ================================================================
 */

@Composable
private fun CurrentExperimentCard() {

    Card(
        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(
                16.dp
            ),

        colors =
            CardDefaults
                .cardColors(
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
                    "Current experiment",

                style =
                    MaterialTheme
                        .typography
                        .titleMedium
            )

            Text(
                text =
                    "We are not forcing LE Audio yet.\n\n" +
                        "The previous Android cached UUID list " +
                        "did not contain ASCS 0x184E.\n\n" +
                        "This test asks the Buds' live LE GATT " +
                        "server directly which services it exposes.\n\n" +
                        "The two results we care about are:\n\n" +
                        "ASCS 0x184E\n" +
                        "PACS 0x1850",

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
