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
 * Experimental LE Audio version.
 *
 * Normal Bluetooth APIs run from this normal Android app process.
 *
 * Only the privileged LE Audio Binder transaction is routed
 * through Shizuku.
 */
@OptIn(
    ExperimentalMaterial3Api::class
)
@Composable
fun HomeScreen(
    audioRoutingManager:
        AudioRoutingManager,
    shizukuManager:
        ShizukuManager,
    onSetupClick:
        () -> Unit,
    modifier:
        Modifier = Modifier
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
                Arrangement
                    .spacedBy(
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
             * EXPERIMENTAL LE AUDIO
             * ====================================================
             */

            ForceLeAudioCard(
                isWorking =
                    forcingLeAudio,

                result =
                    leAudioResult,

                onForceLeAudio = {

                    /*
                     * Shizuku itself must already be ready.
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
                     * The classic HFP representation normally gives
                     * us the physical Buds Bluetooth identity address.
                     *
                     * We are NOT routing through HFP here.
                     *
                     * We only use it to identify the paired Buds.
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

                        leAudioResult =
                            """
                            No Bluetooth headset found.

                            Connect Antonio's Buds4 Pro first.
                            """.trimIndent()

                        return@ForceLeAudioCard
                    }

                    val address =
                        budsDevice
                            .deviceInfo
                            .address

                    if (
                        address.isBlank()
                    ) {

                        leAudioResult =
                            """
                            Android did not expose the
                            Bluetooth identity address.

                            Disconnect and reconnect
                            the Buds, then try again.
                            """.trimIndent()

                        return@ForceLeAudioCard
                    }

                    forcingLeAudio =
                        true

                    leAudioResult =
                        """
                        Starting normal-process
                        Bluetooth + Shizuku Binder test...

                        Device:
                        ${budsDevice.name}

                        Address:
                        $address
                        """.trimIndent()

                    coroutineScope.launch {

                        val result =
                            withContext(
                                Dispatchers.IO
                            ) {

                                LeAudioShizukuBridge
                                    .forceLeAudio(
                                        context =
                                            context,

                                        address =
                                            address
                                    )
                            }

                        leAudioResult =
                            result

                        /*
                         * Android's LE Audio stack is asynchronous.
                         *
                         * Once ALLOWED is accepted, give Samsung
                         * several seconds to create TYPE_BLE_HEADSET.
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
                             * Your AudioRoutingManager is the strict
                             * BLE diagnostic build.
                             *
                             * It will now succeed ONLY if Samsung
                             * actually created TYPE_BLE_HEADSET.
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
             * INFORMATION
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
 * LE AUDIO CARD
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
                Arrangement
                    .spacedBy(
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
                        "Experimental LE Audio",

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
                    "Normal Bluetooth runs inside BTMicFix. " +
                        "Only the privileged LE Audio Binder call " +
                        "is forwarded through Shizuku.",

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

                if (isWorking) {

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
                    if (isWorking) {

                        "Forcing LE Audio…"

                    } else {

                        "Force LE Audio (Binder)"
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
 * NORMAL ROUTING BUTTON
 * ================================================================
 */

@Composable
private fun RoutingControlButton(
    routingState:
        RoutingState,

    onEnableRouting:
        () -> Unit,

    onDisableRouting:
        () -> Unit,

    onRetry:
        () -> Unit
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
                    "Enable Routing",

                    style =
                        MaterialTheme
                            .typography
                            .labelLarge
                )
            }
        }

        is RoutingState.Routing -> {

            Button(
                onClick = {
                },

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            56.dp
                        ),

                enabled =
                    false,

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
                    "Retry"
                )
            }
        }
    }
}

/*
 * ================================================================
 * INFORMATION CARD
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
                Arrangement
                    .spacedBy(
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
                        "The button asks Android to enable the " +
                        "Buds4 Pro LE Audio profile using a " +
                        "Shizuku-wrapped Bluetooth Binder.\n\n" +
                        "If Samsung creates a BLE Headset route, " +
                        "the strict AudioRoutingManager will pick it.",

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
