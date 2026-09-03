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
                onOff(
                    state.notificationAccessEnabled
                )
            )

            StatusRow(
                "Host service",
                if (
                    state.hostRunning
                ) {
                    "RUNNING"
                } else {
                    "STOPPED"
                }
            )

            StatusRow(
                "Listener",
                connected(
                    state.listenerConnected
                )
            )

            StatusRow(
                "Shizuku",
                if (
                    state.shizukuReady
                ) {
                    "READY"
                } else {
                    "WAITING"
                }
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
                "ACTIVE watcher",
                if (
                    state.watcherRegistered
                ) {
                    "REGISTERED"
                } else {
                    "NOT REGISTERED"
                }
            )

            StatusRow(
                "STARTING watcher",
                if (
                    state.startedWatcherRegistered
                ) {
                    "REGISTERED"
                } else {
                    "NOT REGISTERED"
                }
            )

            StatusRow(
                "STARTING event seen",
                yesNo(
                    state.startingEventSeen
                )
            )

            StatusRow(
                "Actual BLE microphone",
                if (
                    state.actualBleInputFound
                ) {
                    "FOUND"
                } else {
                    "NOT FOUND"
                }
            )

            StatusRow(
                "BLE input type",
                audioDeviceTypeName(
                    state.actualBleInputType
                )
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
                "BLE mic capture preference",
                if (
                    state.capturePreferenceApplied
                ) {
                    "ON"
                } else {
                    "OFF"
                }
            )

            StatusRow(
                "Capture source",
                audioSourceName(
                    state.captureAudioSource
                )
            )

            StatusRow(
                "Communication BLE route",
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

private fun onOff(
    value: Boolean
): String {

    return if (
        value
    ) {
        "ON"
    } else {
        "OFF"
    }
}

private fun connected(
    value: Boolean
): String {

    return if (
        value
    ) {
        "CONNECTED"
    } else {
        "WAITING"
    }
}

private fun yesNo(
    value: Boolean
): String {

    return if (
        value
    ) {
        "YES"
    } else {
        "NO"
    }
}

private fun audioDeviceTypeName(
    type: Int
): String {

    return when (
        type
    ) {

        -1 ->
            "UNKNOWN"

        26 ->
            "BLE_HEADSET"

        7 ->
            "BLUETOOTH_SCO"

        else ->
            "Type $type"
    }
}

private fun audioSourceName(
    source: Int
): String {

    return when (
        source
    ) {

        -1 ->
            "UNKNOWN"

        0 ->
            "DEFAULT"

        1 ->
            "MIC"

        5 ->
            "CAMCORDER"

        6 ->
            "VOICE_RECOGNITION"

        7 ->
            "VOICE_COMMUNICATION"

        9 ->
            "UNPROCESSED"

        10 ->
            "VOICE_PERFORMANCE"

        else ->
            "AudioSource $source"
    }
}
