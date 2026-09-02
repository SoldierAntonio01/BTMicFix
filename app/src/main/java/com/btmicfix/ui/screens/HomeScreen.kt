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
import com.btmicfix.ui.components.StatusCard
import com.btmicfix.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * BTMicFix LE Audio activation screen.
 *
 * IMPORTANT:
 *
 * Android's LE Audio connection gate requires the cached
 * BluetoothUuid.LE_AUDIO / ASCS UUID 0x184E.
 *
 * PACS 0x1850 is useful diagnostic information but is NOT
 * required in BluetoothDevice.getUuids() before connecting.
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
     * LIVE SCAN
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
     * CACHE + CONNECT
     * ============================================================
     */

    var cacheWorking by
        remember {

            mutableStateOf(
                false
            )
        }

    var cacheResult by
        remember {

            mutableStateOf<String?>(
                null
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

            /*
             * ====================================================
             * LIVE GATT SCAN
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

                onScan = scan@{

                    val buds =
                        findBudsAudioDevice(
                            availableDevices
                        )

                    if (
                        buds ==
                        null
                    ) {

                        scanResult =
                            """
                            No Bluetooth headset found.

                            Connect Antonio's Buds4 Pro first.
                            """.trimIndent()

                        return@scan
                    }

                    val preferredName =
                        buds.name

                    scanning =
                        true

                    ascsFound =
                        null

                    pacsFound =
                        null

                    scanResult =
                        """
                        Running live LE GATT scan...

                        Device:
                        $preferredName
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

                        ascsFound =
                            result.hasAscs

                        pacsFound =
                            result.hasPacs

                        scanResult =
                            result.text

                        scanning =
                            false
                    }
                }
            )

            /*
             * ====================================================
             * REFRESH + CONNECT
             * ====================================================
             */

            CacheConnectCard(
                working =
                    cacheWorking,

                result =
                    cacheResult,

                onRun = cache@{

                    if (
                        !shizukuManager
                            .isAvailable()
                    ) {

                        cacheResult =
                            """
                            Shizuku is not ready.

                            Open Shizuku and make sure
                            it says Running.
                            """.trimIndent()

                        return@cache
                    }

                    val buds =
                        findBudsAudioDevice(
                            availableDevices
                        )

                    if (
                        buds ==
                        null
                    ) {

                        cacheResult =
                            """
                            No Bluetooth headset found.

                            Connect Antonio's Buds4 Pro first.
                            """.trimIndent()

                        return@cache
                    }

                    val preferredName =
                        buds.name

                    cacheWorking =
                        true

                    cacheResult =
                        """
                        STEP 1 OF 4

                        Verifying the Buds'
                        live LE Audio services...
                        """.trimIndent()

                    coroutineScope.launch {

                        /*
                         * ==========================================
                         * STEP 1
                         * LIVE GATT
                         * ==========================================
                         */

                        val live =
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

                        ascsFound =
                            live.hasAscs

                        pacsFound =
                            live.hasPacs

                        scanResult =
                            live.text

                        /*
                         * Live scan should still confirm both
                         * core services actually exist on the Buds.
                         *
                         * This is different from Android's cache.
                         */
                        if (
                            !live.success ||
                            !live.hasAscs ||
                            !live.hasPacs
                        ) {

                            cacheResult =
                                """
                                STOPPED SAFELY

                                Live GATT did not confirm
                                both LE Audio services.

                                ASCS 0x184E:
                                ${yesNo(live.hasAscs)}

                                PACS 0x1850:
                                ${yesNo(live.hasPacs)}

                                No LE profile connection
                                was attempted.
                                """.trimIndent()

                            cacheWorking =
                                false

                            return@launch
                        }

                        /*
                         * ==========================================
                         * STEP 2
                         * ANDROID CACHE
                         * ==========================================
                         */

                        cacheResult =
                            """
                            STEP 2 OF 4

                            Live services confirmed:

                            ASCS 0x184E: YES
                            PACS 0x1850: YES

                            Checking / refreshing Android's
                            LE Audio UUID cache...
                            """.trimIndent()

                        val refresh =
                            withContext(
                                Dispatchers.IO
                            ) {

                                LeAudioCacheRefresher.refresh(
                                    context =
                                        context,

                                    preferredDeviceName =
                                        preferredName
                                )
                            }

                        /*
                         * =================================================
                         * IMPORTANT CORRECTION
                         * =================================================
                         *
                         * OLD GATE:
                         *
                         * require ASCS && PACS
                         *
                         * NEW CORRECT GATE:
                         *
                         * require ASCS 0x184E
                         *
                         * AOSP LeAudioService.connect() checks
                         * BluetoothUuid.LE_AUDIO / 184E.
                         */

                        if (
                            !refresh.cacheUpdated ||
                            !refresh.hasAscs
                        ) {

                            cacheResult =
                                refresh.text

                            cacheWorking =
                                false

                            return@launch
                        }

                        /*
                         * ==========================================
                         * STEP 3
                         * LE AUDIO CONNECT
                         * ==========================================
                         */

                        cacheResult =
                            refresh.text +
                                """



                                STEP 3 OF 4

                                Android now has:

                                ASCS / LE_AUDIO 0x184E = YES

                                PACS cached:
                                ${yesNo(refresh.hasPacs)}

                                PACS is not required by the
                                LeAudioService connection gate.

                                Attempting LE Audio connection...
                                """.trimIndent()

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

                        /*
                         * Only continue when the bridge proves
                         * the profile really reached CONNECTED.
                         */

                        val connected =
                            connectResult.contains(
                                "ACCEPTED - LE AUDIO CONNECTED"
                            ) ||
                                connectResult.contains(
                                    "ACCEPTED - ALREADY CONNECTED"
                                )

                        if (!connected) {

                            cacheResult =
                                refresh.text +
                                    "\n\n" +
                                    connectResult

                            cacheWorking =
                                false

                            return@launch
                        }

                        /*
                         * ==========================================
                         * STEP 4
                         * WAIT FOR BLE HEADSET ROUTE
                         * ==========================================
                         */

                        cacheResult =
                            refresh.text +
                                "\n\n" +
                                connectResult +
                                """



                                STEP 4 OF 4

                                LE Audio profile connected.

                                Waiting for Android to expose
                                TYPE_BLE_HEADSET...
                                """.trimIndent()

                        delay(
                            2500
                        )

                        val routingResult =
                            audioRoutingManager
                                .routeToFirstAvailableBluetooth()

                        cacheResult =
                            refresh.text +
                                "\n\n" +
                                connectResult +
                                """



                                ===== FINAL ROUTING =====

                                $routingResult

                                Look at the status card at
                                the top of BTMicFix.

                                SUCCESS TARGET:

                                Antonio's Buds4 Pro
                                BLE Headset / LE Audio
                                """.trimIndent()

                        cacheWorking =
                            false
                    }
                }
            )

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
 * FIND CONNECTED BUDS
 * ================================================================
 */

private fun findBudsAudioDevice(
    devices:
        List<AudioRoutingManager.BluetoothAudioDevice>
): AudioRoutingManager.BluetoothAudioDevice? {

    return devices
        .firstOrNull {

            it.deviceInfo.type ==
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        ?: devices
            .firstOrNull {

                it.deviceInfo.type ==
                    AudioDeviceInfo.TYPE_BLE_HEADSET
            }
        ?: devices.firstOrNull()
}

/*
 * ================================================================
 * LIVE SCAN CARD
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

            if (
                ascsFound != null ||
                pacsFound != null
            ) {

                HorizontalDivider()

                Text(
                    text =
                        "ASCS 0x184E: " +
                            when (ascsFound) {

                                true ->
                                    "YES"

                                false ->
                                    "NO"

                                null ->
                                    "UNKNOWN"
                            },

                    fontWeight =
                        FontWeight.Bold
                )

                Text(
                    text =
                        "PACS 0x1850: " +
                            when (pacsFound) {

                                true ->
                                    "YES"

                                false ->
                                    "NO"

                                null ->
                                    "UNKNOWN"
                            },

                    fontWeight =
                        FontWeight.Bold
                )
            }

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
                    ButtonDefaults.buttonColors(
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
                            Icons.Default.Search,

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
                        "Scanning…"
                    } else {
                        "Scan Buds LE Services"
                    }
                )
            }

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
 * CACHE + CONNECT CARD
 * ================================================================
 */

@Composable
private fun CacheConnectCard(
    working: Boolean,
    result: String?,
    onRun: () -> Unit
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
                        Icons.Default.Refresh,

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
                        "Android LE Cache + Connection",

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
                    "Requires Android's cached LE_AUDIO " +
                        "UUID 0x184E. PACS 0x1850 is " +
                        "diagnostic only.",

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
                    onRun,

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
                            Icons.Default.BluetoothAudio,

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
                        "Working…"
                    } else {
                        "Connect Buds with LE Audio"
                    }
                )
            }

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
 * ROUTING BUTTON
 * ================================================================
 */

@Composable
private fun RoutingControlButton(
    routingState: RoutingState,
    onEnableRouting: () -> Unit,
    onDisableRouting: () -> Unit,
    onRetry: () -> Unit
) {

    when (routingState) {

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
                        Icons.Default.PowerSettingsNew,

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
                    "Retry"
                )
            }
        }
    }
}

/*
 * ================================================================
 * INFO CARD
 * ================================================================
 */

@Composable
private fun CurrentExperimentCard() {

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
                    "Where we are now",

                style =
                    MaterialTheme
                        .typography
                        .titleMedium,

                fontWeight =
                    FontWeight.Bold
            )

            Text(
                text =
                    "Live Buds GATT:\n" +
                        "184E = YES\n" +
                        "1850 = YES\n" +
                        "184F = YES\n\n" +
                        "Android cache after Shizuku refresh:\n" +
                        "184E = YES\n" +
                        "184F = YES\n" +
                        "1850 = not cached\n\n" +
                        "That is enough to attempt LE Audio " +
                        "because Android's LeAudioService checks " +
                        "the LE_AUDIO / 184E UUID.",

                style =
                    MaterialTheme
                        .typography
                        .bodySmall
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
