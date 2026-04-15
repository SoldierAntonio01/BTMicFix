package com.btmicfix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.btmicfix.shizuku.ShizukuManager
import com.btmicfix.shizuku.ShizukuManager.ShizukuStatus
import com.btmicfix.ui.theme.*

/**
 * Card showing Shizuku connection status.
 * Provides action buttons based on the current state.
 */
@Composable
fun ShizukuStatusCard(
    shizukuManager: ShizukuManager,
    modifier: Modifier = Modifier,
) {
    val status by shizukuManager.status.collectAsState()

    val (icon, statusText, statusColor, actionLabel) = when (status) {
        ShizukuStatus.UNKNOWN -> ShizukuDisplayInfo(
            icon = Icons.Default.Info,
            text = "Checking Shizuku…",
            color = StatusIdle,
            action = null,
        )
        ShizukuStatus.NOT_INSTALLED -> ShizukuDisplayInfo(
            icon = Icons.Default.Close,
            text = "Shizuku not installed (optional)",
            color = StatusIdle,
            action = null,
        )
        ShizukuStatus.NOT_RUNNING -> ShizukuDisplayInfo(
            icon = Icons.Default.Warning,
            text = "Shizuku not running",
            color = StatusRouting,
            action = null,
        )
        ShizukuStatus.PERMISSION_NEEDED -> ShizukuDisplayInfo(
            icon = Icons.Default.Warning,
            text = "Shizuku permission needed",
            color = StatusRouting,
            action = "Grant Permission",
        )
        ShizukuStatus.READY -> ShizukuDisplayInfo(
            icon = Icons.Default.CheckCircle,
            text = "Shizuku ready (fallback enabled)",
            color = StatusActive,
            action = null,
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(24.dp),
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Shizuku",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (actionLabel != null) {
                TextButton(
                    onClick = { shizukuManager.requestPermission() },
                    colors = ButtonDefaults.textButtonColors(contentColor = Purple40),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

private data class ShizukuDisplayInfo(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val text: String,
    val color: androidx.compose.ui.graphics.Color,
    val action: String?,
)
