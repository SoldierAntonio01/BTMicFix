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
 * Main BTMicFix screen.
 *
 * Experimental LE Audio build.
 *
 * Important:
 *
 * We DO NOT use AudioDeviceInfo.address for the LE Audio request.
 *
 * Samsung masked that address as:
 *
 * XX:XX:XX:XX:xx:xx
 *
 * Instead, we pass the headset name to LeAudioShizukuBridge.
 *
 * LeAudioShizukuBridge then finds the real paired
 * BluetoothDevice through BluetoothAdapter.bondedDevices.
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

    /*
     * ============================================================
     * AVAILABLE BLUETOOTH DEVICES
     * ============================================================
     */

    val availableDevices by
        audioRoutingManager
            .availableDevices
            .collectAsState()

    /*
     * ============================================================
     * NORMAL APP CONTEXT
     * ============================================================
     *
     * BluetoothManager / BluetoothAdapter work here.
     *
     * They did NOT work properly inside the Shizuku UserService.
     */

    val context =
        LocalContext
            .current
            .applicationContext

    /*
     * ============================================================
     * COROUTINES
     * ============================================================
     */

    val coroutineScope =
        rememberCoroutineScope()

    /*
     * ============================================================
     * LE AUDIO TEST STATE
     * ============================================================
     */

    var leAudioResult by
        remember {

            mutableStateOf<String?>(
                null
            )
        }

    var forcingLeAudio by
        remember {

            mutableStateOf(
                false
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
             * CURRENT ROUTING STATUS
             * ====================================================
             */

            StatusCard(
                routingState =
                    routingState
            )

            /*
             * ====================================================
             * NORMAL ROUTING CONTROL
             * ====================================================
             */

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
             * CONNECTED BLUETOOTH DEVICES
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
             * EXPERIMENTAL LE AUDIO BUTTON
             * ====================================================
             */

            ForceLeAudioCard(
                isWorking =
                    forcingLeAudio,

                result =
                    leAudioResult,

                onForceLeAudio = {

                    /*
                     * ------------------------------------------------
                     * SHIZUKU MUST BE READY
                     * ------------------------------------------------
                     */

                    if (
                        !shizukuManager
                            .isAvailable()
                    ) {

                        leAudioResult =
                            """
                            Shizuku is not ready.

                            Open Shizuku and make sure
                            it says Running.
                            """.trimIndent()

                        return@ForceLeAudioCard
                    }

                    /*
                     * ------------------------------------------------
                     * FIND OUR CONNECTED HEADSET
                     * ------------------------------------------------
                     *
                     * Prefer the HFP/SCO endpoint because that is
                     * currently how Samsung exposes the Buds.
                     *
                     * We only use this endpoint to get the NAME.
                     *
                     * We DO NOT use its masked address.
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

                    /*
                     * ------------------------------------------------
                     * NO HEADSET
                     * ------------------------------------------------
                     */

                    if (
                        budsDevice ==
                        null
                    ) {

                        leAudioResult =
                            """
                            No Bluetooth headset found.

                            Connect Antonio's Buds4 Pro first.
                            """.trimIndent()

                        return@ForceLeAudioCard
                    }

                    /*
                     * ------------------------------------------------
                     * THIS IS THE IMPORTANT FIX
                     * ------------------------------------------------
                     *
                     * OLD:
                     *
                     * address = address
                     *
                     * NEW:
                     *
                     * preferredDeviceName = preferredName
                     */

                    val preferredName =
                        budsDevice.name

                    forcingLeAudio =
                        true

                    leAudioResult =
                        """
                        Finding the real paired
                        BluetoothDevice...

                        Selected headset:
                        $preferredName

                        AudioDeviceInfo MAC is intentionally
                        NOT being used.
                        """.trimIndent()

                    /*
                     * ------------------------------------------------
                     * RUN BINDER TEST
                     * ------------------------------------------------
                     */

                    coroutineScope.launch {

                        val result =
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

                        leAudioResult =
                            result

                        /*
                         * ------------------------------------------------
                         * IF LE AUDIO POLICY WAS ACCEPTED
                         * ------------------------------------------------
                         *
                         * Samsung's Bluetooth stack is asynchronous.
                         *
                         * Give it time to create TYPE_BLE_HEADSET.
                         */

                        if (
                            result.contains(
                                "ACCEPTED"
                            )
                        ) {

                            delay(
                                5000
                            )

                            /*
                             * Your strict AudioRoutingManager will
                             * now succeed ONLY if Android created
                             * TYPE_BLE_HEADSET.
                             */

                            audioRoutingManager
                                .routeToFirstAvailableBluetooth()
                        }

                        forcingLeAudio =
                            false
                    }
                }
            )

            /*
             * ====================================================
             * CURRENT EXPERIMENT INFO
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
 * FORCE LE AUDIO CARD
 * ================================================================
 */

@Composable
private fun ForceLeAudioCard(
    isWorking: Boolean,
    result: String?,
    onForceLeAudio: () -> Unit
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

            /*
             * ----------------------------------------------------
             * TITLE
             * ----------------------------------------------------
             */

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
                        "Experimental LE Audio",

                    style =
                        MaterialTheme
                            .typography
                            .titleMedium,

                    fontWeight =
                        FontWeight.Bold
                )
            }

            /*
             * ----------------------------------------------------
             * DESCRIPTION
             * ----------------------------------------------------
             */

            Text(
                text =
                    "Finds your actual paired Buds4 Pro " +
                        "BluetoothDevice, then forwards only " +
                        "the privileged LE Audio Binder call " +
                        "through Shizuku.",

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
             * FORCE LE AUDIO BUTTON
             * ----------------------------------------------------
             */

            Button(
                onClick =
                    onForceLeAudio,

                enabled =
                    !isWorking,

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

                if (
                    isWorking
                ) {

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
                    if (
                        isWorking
                    ) {

                        "Forcing LE Audio…"

                    } else {

                        "Force LE Audio (Binder)"
                    }
                )
            }

            /*
             * ----------------------------------------------------
             * RESULT TEXT
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
 * NORMAL ROUTING BUTTON
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

        /*
         * --------------------------------------------------------
         * IDLE
         * --------------------------------------------------------
         */

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
                        null,

                    modifier =
                        Modifier.size(
                            20.dp
                        )
                )

                Spacer(
                    modifier =
                        Modifier.width(
                            8.dp
                        )
                )

                Text(
                    text =
                        "Enable Routing",

                    style =
                        MaterialTheme
                            .typography
                            .labelLarge
                )
            }
        }

        /*
         * --------------------------------------------------------
         * ROUTING
         * --------------------------------------------------------
         */

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
                        ),

                shape =
                    RoundedCornerShape(
                        16.dp
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
                    text =
                        "Routing…"
                )
            }
        }

        /*
         * --------------------------------------------------------
         * ACTIVE
         * --------------------------------------------------------
         */

        is RoutingState.Active -> {

            OutlinedButton(
                onClick =
                    onDisableRouting,

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            56.dp
                        ),

                shape =
                    RoundedCornerShape(
                        16.dp
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
                    text =
                        "Disable Routing"
                )
            }
        }

        /*
         * --------------------------------------------------------
         * FAILED
         * --------------------------------------------------------
         */

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

                shape =
                    RoundedCornerShape(
                        16.dp
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
                        Icons.Default
                            .Refresh,

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
                    text =
                        "Retry"
                )
            }
        }
    }
}

/*
 * ================================================================
 * CURRENT EXPERIMENT CARD
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
                    "Normal music stays on Samsung SSC/UHQ.\n\n" +
                        "BTMicFix now identifies the real paired " +
                        "Buds4 Pro through BluetoothAdapter rather " +
                        "than the masked AudioDeviceInfo address.\n\n" +
                        "Then Shizuku attempts the privileged " +
                        "LE Audio connection-policy call.\n\n" +
                        "If Android creates a BLE Headset endpoint, " +
                        "the strict AudioRoutingManager will use it.",

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
