package com.btmicfix.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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

/**
 * Main dashboard screen.
 *
 * Shows the current routing status, available Bluetooth devices,
 * Shizuku status, and provides manual routing controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    audioRoutingManager: AudioRoutingManager,
    shizukuManager: ShizukuManager,
    onSetupClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val routingState by audioRoutingManager.routingState.collectAsState()
    val availableDevices by audioRoutingManager.availableDevices.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "BTMicFix",
                        fontWeight = FontWeight.Bold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceDark,
                    titleContentColor = Purple80,
                ),
                actions = {
                    IconButton(onClick = onSetupClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Setup",
                            tint = Purple80,
                        )
                    }
                },
            )
        },
        containerColor = SurfaceDark,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Primary status card
            StatusCard(routingState = routingState)

            // Route / Stop button
            RoutingControlButton(
                routingState = routingState,
                onEnableRouting = {
                    audioRoutingManager.routeToFirstAvailableBluetooth()
                },
                onDisableRouting = {
                    audioRoutingManager.clearRouting()
                },
                onRetry = {
                    audioRoutingManager.routeToFirstAvailableBluetooth()
                },
            )

            // Available Bluetooth devices
            DeviceSelector(
                devices = availableDevices,
                onDeviceSelected = { device ->
                    audioRoutingManager.routeToBluetooth(device.deviceInfo)
                },
            )

            // Shizuku status (optional)
            ShizukuStatusCard(shizukuManager = shizukuManager)

            // How it works explainer
            HowItWorksCard()

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Main routing control button — changes label/action based on current state.
 */
@Composable
private fun RoutingControlButton(
    routingState: RoutingState,
    onEnableRouting: () -> Unit,
    onDisableRouting: () -> Unit,
    onRetry: () -> Unit,
) {
    when (routingState) {
        is RoutingState.Idle -> {
            Button(
                onClick = onEnableRouting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Purple40),
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Enable Routing", style = MaterialTheme.typography.labelLarge)
            }
        }
        is RoutingState.Routing -> {
            Button(
                onClick = { },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = false,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = StatusRouting.copy(alpha = 0.3f),
                    disabledContainerColor = StatusRouting.copy(alpha = 0.2f),
                ),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = StatusRouting,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Routing…",
                    style = MaterialTheme.typography.labelLarge,
                    color = StatusRouting,
                )
            }
        }
        is RoutingState.Active -> {
            OutlinedButton(
                onClick = onDisableRouting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusActive),
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Disable Routing", style = MaterialTheme.typography.labelLarge)
            }
        }
        is RoutingState.Failed -> {
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = StatusFailed.copy(alpha = 0.8f)),
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retry", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Informational card explaining how the app works.
 */
@Composable
private fun HowItWorksCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "How it works",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            val steps = listOf(
                "AI apps like ChatGPT fail to switch your Bluetooth earbuds from music mode (A2DP) to mic mode (SCO/HFP).",
                "BTMicFix forces this switch using Android's setCommunicationDevice API.",
                "Once enabled, your earbuds' microphone becomes the active input for all voice apps.",
                "With background mode, this happens automatically whenever your earbuds connect.",
            )

            steps.forEachIndexed { index, step ->
                Row {
                    Text(
                        text = "${index + 1}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Purple40,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(20.dp),
                    )
                    Text(
                        text = step,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
