package com.btmicfix.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.btmicfix.audio.AudioRoutingManager.RoutingState
import com.btmicfix.ui.theme.*

/**
 * Primary status card showing the current audio routing state.
 * Uses animated colors and icons for a premium, reactive feel.
 */
@Composable
fun StatusCard(
    routingState: RoutingState,
    modifier: Modifier = Modifier,
) {
    val (statusColor, statusIcon, statusTitle, statusSubtitle) = when (routingState) {
        is RoutingState.Idle -> StatusInfo(
            color = StatusIdle,
            icon = Icons.Default.BluetoothDisabled,
            title = "Idle",
            subtitle = "No Bluetooth device routed",
        )
        is RoutingState.Routing -> StatusInfo(
            color = StatusRouting,
            icon = Icons.Default.BluetoothConnected,
            title = "Routing…",
            subtitle = "Connecting to ${routingState.deviceName}",
        )
        is RoutingState.Active -> StatusInfo(
            color = StatusActive,
            icon = Icons.Default.Mic,
            title = "Active",
            subtitle = "Mic routed to ${routingState.deviceName}",
        )
        is RoutingState.Failed -> StatusInfo(
            color = StatusFailed,
            icon = Icons.Default.Error,
            title = "Failed",
            subtitle = routingState.reason,
        )
    }

    val animatedColor by animateColorAsState(
        targetValue = statusColor,
        animationSpec = tween(durationMillis = 500),
        label = "statusColorAnimation",
    )

    val glowAlpha by animateFloatAsState(
        targetValue = if (routingState is RoutingState.Active) 1f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "glowAnimation",
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (routingState is RoutingState.Active) {
                        Modifier.border(
                            width = 1.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    StatusActive.copy(alpha = 0.5f),
                                    Purple40.copy(alpha = 0.3f),
                                )
                            ),
                            shape = RoundedCornerShape(20.dp),
                        )
                    } else Modifier
                )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Status indicator circle
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(animatedColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = statusTitle,
                        modifier = Modifier.size(40.dp),
                        tint = animatedColor,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = statusTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = animatedColor,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = statusSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Internal data class for status display properties.
 */
private data class StatusInfo(
    val color: androidx.compose.ui.graphics.Color,
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
)
