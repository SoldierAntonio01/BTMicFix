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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.btmicfix.audio.AudioRoutingManager
import com.btmicfix.audio.AudioRoutingManager.RoutingState
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
 * Adds an experimental Shizuku button that attempts to enable
 * the Buds' hidden LE Audio profile.
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
             * Normal routing status.
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
             * Available BT devices.
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
             * Existing Shizuku card.
             */
            ShizukuStatusCard(
                shizukuManager =
                    shizukuManager
            )

            /*
             * NEW:
             * Force LE Audio button.
             */
            ForceLeAudioCard(
                isWorking =
                    forcingLeAudio,

                result =
                    leAudioResult,

                onForceLeAudio = {

                    /*
                     * Prefer the HFP/SCO representation of the Buds
                     * because it normally exposes their Bluetooth
                     * identity address.
                     */
                    val budsDevice =
                        availableDevices
                            .firstOrNull {

                                it.deviceInfo.type ==
                                    AudioDeviceInfo
                                        .TYPE_BLUETOOTH_SCO
                            }
                            ?: availableDevices
                                .firstOrNull()

                    if (
                        budsDevice ==
                        null
                    ) {

                        leAudioResult =
                            """
                            No Bluetooth headset was found.

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
                            Android did not expose a Bluetooth
                            address for this headset.

                            Disconnect and reconnect the Buds,
                            then try again.
                            """.trimIndent()

                        return@ForceLeAudioCard
                    }

                    forcingLeAudio =
                        true

                    leAudioResult =
                        """
                        Starting Shizuku LE Audio request...

                        Device:
                        ${budsDevice.name}

                        Address:
                        $address
                        """.trimIndent()

                    coroutineScope.launch {

                        /*
                         * Ensure the Shizuku UserService is alive.
                         */
                        shizukuManager
                            .bindPrivilegedService()

                        /*
                         * Give Binder time to connect.
                         */
                        delay(
                            1000
                        )

                        val result =
                            withContext(
                                Dispatchers.IO
                            ) {

                                shizukuManager
                                    .forceLeAudio(
                                        address
                                    )
                            }

                        leAudioResult =
                            result

                        /*
                         * If Android accepted the LE Audio request,
                         * wait a little longer and have our strict
                         * AudioRoutingManager check again.
                         */
                        if (
                            result.contains(
                                "SUCCESS"
                            ) ||
                            result.contains(
                                "ACCEPTED"
                            )
                        ) {

                            delay(
                                2500
                            )

                            audioRoutingManager
                                .routeToFirstAvailableBluetooth()
                        }

                        forcingLeAudio =
                            false
                    }
                }
            )

            HowItWorksCard()

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
                Arrangement
                    .spacedBy(
                        12.dp
                    )
        ) {

            Row(
                verticalAlignment =
                    Alignment
                        .CenterVertically
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
                    "Uses Shizuku to ask Samsung's Bluetooth stack " +
                        "to enable the Buds4 Pro LE Audio profile.",
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
                    if (isWorking) {
                        "Enabling LE Audio…"
                    } else {
                        "Force LE Audio (Shizuku)"
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
 * NORMAL ROUTING CONTROL
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
 * INFO CARD
 * ================================================================
 */

@Composable
private fun HowItWorksCard() {

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
                    "Normal music: Samsung SSC/UHQ.\n\n" +
                        "Voice microphone: trying to activate " +
                        "Bluetooth LE Audio instead of HFP/SCO.\n\n" +
                        "If Samsung accepts LE Audio, the strict " +
                        "routing manager will detect BLE Headset.",
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
