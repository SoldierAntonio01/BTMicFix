package com.btmicfix.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.btmicfix.automation.VoiceAccessAutomationState
import com.btmicfix.ui.theme.SurfaceCard

@Composable
fun VoiceAccessAutomationStatusCard() {

    val state by
        VoiceAccessAutomationState
            .state
            .collectAsState()

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
                    "Voice Access Automation",

                style =
                    MaterialTheme
                        .typography
                        .titleMedium,

                fontWeight =
                    FontWeight.Bold
            )

            StatusRow(
                "Notification Access",
                yesNo(
                    state.notificationAccessEnabled
                )
            )

            StatusRow(
                "Host service",
                running(
                    state.hostRunning
                )
            )

            StatusRow(
                "Listener",
                connected(
                    state.listenerConnected
                )
            )

            StatusRow(
                "Shizuku",
                ready(
                    state.shizukuReady
                )
            )

            StatusRow(
                "AppOps UserService",
                connected(
                    state.userServiceConnected
                )
            )

            StatusRow(
                "Voice Access UID",
                if (
                    state.voiceAccessUidFound
                ) {
                    "FOUND"
                } else {
                    "NOT FOUND"
                }
            )

            StatusRow(
                "RECORD_AUDIO watcher",
                if (
                    state.watcherRegistered
                ) {
                    "REGISTERED"
                } else {
                    "NOT REGISTERED"
                }
            )

            StatusRow(
                "Voice Access RECORD_AUDIO",
                if (
                    state.recordAudioActive
                ) {
                    "ACTIVE"
                } else {
                    "INACTIVE"
                }
            )

            StatusRow(
                "Automatic BLE route",
                if (
                    state.autoRoutingActive
                ) {
                    "ON"
                } else {
                    "OFF"
                }
            )

            HorizontalDivider()

            Text(
                text =
                    "Last event",

                fontWeight =
                    FontWeight.Bold,

                style =
                    MaterialTheme
                        .typography
                        .bodySmall
            )

            Text(
                text =
                    state.lastMessage,

                style =
                    MaterialTheme
                        .typography
                        .bodySmall
            )
        }
    }
}

@Composable
private fun StatusRow(
    name: String,
    value: String
) {

    Row(
        modifier =
            Modifier.fillMaxWidth(),

        horizontalArrangement =
            Arrangement.SpaceBetween
    ) {

        Text(
            text =
                name,

            style =
                MaterialTheme
                    .typography
                    .bodySmall
        )

        Text(
            text =
                value,

            style =
                MaterialTheme
                    .typography
                    .bodySmall,

            fontWeight =
                FontWeight.Bold
        )
    }
}

private fun yesNo(
    value: Boolean
): String {

    return if (value) {
        "ON"
    } else {
        "OFF"
    }
}

private fun running(
    value: Boolean
): String {

    return if (value) {
        "RUNNING"
    } else {
        "STOPPED"
    }
}

private fun connected(
    value: Boolean
): String {

    return if (value) {
        "CONNECTED"
    } else {
        "WAITING"
    }
}

private fun ready(
    value: Boolean
): String {

    return if (value) {
        "READY"
    } else {
        "WAITING"
    }
}
