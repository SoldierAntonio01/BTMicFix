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
 * BTMicFix LE Audio diagnostic / activation screen.
 *
 * Full safe sequence:
 *
 * 1. Verify Buds expose real LE Audio services over live GATT.
 *
 * 2. Ask Android's own Bluetooth stack to refresh UUIDs
 *    specifically over TRANSPORT_LE through Shizuku.
 *
 * 3. Verify BluetoothDevice.getUuids() now contains:
 *
 *    ASCS 0x184E
 *    PACS 0x1850
 *
 * 4. ONLY if Android's cache is correct:
 *
 *    attempt BluetoothLeAudio connection.
 *
 * 5. ONLY if LE Audio actually connects:
 *
 *    allow AudioRoutingManager to select TYPE_BLE_HEADSET.
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

    /*
     * ============================================================
     * ROUTING STATE
     * ============================================================
     */

    val routingState by
        audioRoutingManager
            .routingState
            .collectAsState()

    val availableDevices by
        audioRoutingManager
            .availableDevices
            .collectAsState()

    /*
     * ============================================================
     * CONTEXT
     * ============================================================
     */

    val context =
        LocalContext
            .current
            .applicationContext

    val coroutineScope =
        rememberCoroutineScope()

    /*
     * ============================================================
     * LIVE GATT SCAN STATE
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
     * CACHE / CONNECTION STATE
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
             * AUDIO ROUTING STATUS
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
                        Running safe live LE GATT scan...

                        Device:
                        $preferredName

                        HFP/A2DP profile policies are
                        not being changed.
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
             * CACHE REFRESH + LE CONNECTION
             * ====================================================
             */

            CacheConnectCard(
                working =
                    cacheWorking,

                result =
                    cacheResult,

                onRun = cache@{

                    /*
                     * ------------------------------------------------
                     * SHIZUKU MUST BE READY
                     * ------------------------------------------------
                     */

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

                    /*
                     * ------------------------------------------------
                     * FIND CONNECTED BUDS
                     * ------------------------------------------------
                     */

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

                        Verifying live LE Audio
                        services on the Buds...

                        Device:
                        $preferredName
                        """.trimIndent()

                    coroutineScope.launch {

                        /*
                         * ============================================
                         * STEP 1
                         * LIVE GATT VALIDATION
                         * ============================================
                         */

                        val liveResult =
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

                        /*
                         * Update the quick scan display too.
                         */

                        ascsFound =
                            liveResult.hasAscs

                        pacsFound =
                            liveResult.hasPacs

                        scanResult =
                            liveResult.text

                        /*
                         * STOP if the core LE Audio services are not
                         * actually visible right now.
                         */

                        if (
                            !liveResult.success ||
                            !liveResult.hasAscs ||
                            !liveResult.hasPacs
                        ) {

                            cacheResult =
                                """
                                STOPPED SAFELY

                                The live Buds service scan did not
                                confirm both required LE Audio services.

                                ASCS 0x184E:
                                ${
                                    if (liveResult.hasAscs) {
                                        "YES"
                                    } else {
                                        "NO"
                                    }
                                }

                                PACS 0x1850:
                                ${
                                    if (liveResult.hasPacs) {
                                        "YES"
                                    } else {
                                        "NO"
                                    }
                                }

                                No Android UUID-cache refresh
                                was attempted.

                                No LE Audio profile connection
                                was attempted.
                                """.trimIndent()

                            cacheWorking =
                                false

                            return@launch
                        }

                        /*
                         * ============================================
                         * STEP 2
                         * REFRESH ANDROID'S OWN LE UUID CACHE
                         * ============================================
                         */

                        cacheResult =
                            """
                            STEP 2 OF 4

                            Live Buds LE Audio services:

                            ASCS 0x184E: YES
                            PACS 0x1850: YES

                            Asking Android's own Bluetooth stack
                            to refresh UUIDs specifically over
                            TRANSPORT_LE through Shizuku...
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
                         * If Android did not update its cache,
                         * DO NOT attempt LE profile connection.
                         */

                        if (
                            !refresh.cacheUpdated ||
                            !refresh.hasAscs ||
                            !refresh.hasPacs
                        ) {

                            cacheResult =
                                refresh.text

                            cacheWorking =
                                false

                            return@launch
                        }

                        /*
                         * ============================================
                         * STEP 3
                         * CONNECT LE AUDIO PROFILE
                         * ============================================
                         */

                        cacheResult =
                            refresh.text +
                                """



                                STEP 3 OF 4

                                Android's cached UUID list now
                                contains the core LE Audio services.

                                Attempting the LE Audio profile
                                connection...
                                """.trimIndent()

                        /*
                         * LeAudioShizukuBridge is the safe version
                         * you already installed.
                         *
                         * It checks the cached UUIDs before calling
                         * BluetoothLeAudio.connect().
                         */

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
                         * ============================================
                         * CONNECTION FAILED / INCOMPLETE
                         * ============================================
                         */

                        if (
                            !connectResult.contains(
                                "ACCEPTED - LE AUDIO CONNECTED"
                            ) &&
                            !connectResult.contains(
                                "ACCEPTED - ALREADY CONNECTED"
                            )
                        ) {

                            cacheResult =
                                refresh.text +
                                    "\n\n" +
                                    connectResult

                            cacheWorking =
                                false

                            return@launch
                        }

                        /*
                         * ============================================
                         * STEP 4
                         * WAIT FOR TYPE_BLE_HEADSET
                         * ============================================
                         */

                        cacheResult =
                            refresh.text +
                                "\n\n" +
                                connectResult +
                                """



                                STEP 4 OF 4

                                LE Audio is connected.

                                Waiting for Android to expose
                                TYPE_BLE_HEADSET...
                                """.trimIndent()

                        delay(
                            2000
                        )

                        /*
                         * Strict AudioRoutingManager:
                         *
                         * It ONLY selects TYPE_BLE_HEADSET.
                         *
                         * It does NOT fall back to HFP/SCO.
                         */

                        val routingResult =
                            audioRoutingManager
                                .routeToFirstAvailableBluetooth()

                        cacheResult =
                            refresh.text +
                                "\n\n" +
                                connectResult +
                                """



                                ===== FINAL ROUTING STEP =====

                                $routingResult

                                If the status at the top of
                                BTMicFix now says:

                                BLE Headset / LE Audio

                                then the Buds microphone is using
                                the LE communication route.
                                """.trimIndent()

                        cacheWorking =
                            false
                    }
                }
            )

            /*
             * ====================================================
             * INFO
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
 * FIND BUDS AUDIO ENDPOINT
 * ================================================================
 */

private fun findBudsAudioDevice(
    devices:
        List<AudioRoutingManager.BluetoothAudioDevice>
): AudioRoutingManager.BluetoothAudioDevice? {

    /*
     * Currently Samsung exposes the Buds through HFP/SCO,
     * so prefer that endpoint for identifying the headset.
     */

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
        ?: devices
            .firstOrNull()
}

/*
 * ================================================================
 * LIVE GATT CARD
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

            Text(
                text =
                    "Safely checks which LE Audio GATT " +
                        "services the Buds are exposing.",

                style =
                    MaterialTheme
                        .typography
                        .bodySmall,

                color =
                    MaterialTheme
                        .colorScheme
                        .onSurfaceVariant
            )

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
 * CACHE REFRESH + LE CONNECT CARD
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
                    "Verifies the live Buds services, refreshes " +
                        "Android's LE UUID cache through Shizuku, " +
                        "then connects LE Audio only if the cache " +
                        "was fixed successfully.",

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

                        "Refresh LE Cache + Connect"
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
                    8.dp
                )
        ) {

            Text(
                text =
                    "Current experiment",

                style =
                    MaterialTheme
                        .typography
                        .titleMedium,

                fontWeight =
                    FontWeight.Bold
            )

            Text(
                text =
                    "Your Buds4 Pro already proved they expose:\n\n" +
                        "ASCS 0x184E = YES\n" +
                        "PACS 0x1850 = YES\n" +
                        "BASS 0x184F = YES\n\n" +
                        "The problem is Android's cached Bluetooth " +
                        "UUID list did not contain those LE services.\n\n" +
                        "This build asks Android itself to rediscover " +
                        "the Buds specifically over TRANSPORT_LE. " +
                        "It only attempts the LE Audio profile after " +
                        "the Android cache actually contains 184E + 1850.",

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
