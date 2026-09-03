package com.btmicfix.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared process-wide status for the Voice Access automation.
 *
 * MainActivity, VoiceAccessMonitorService, and the Compose
 * diagnostic card can all read/write this state.
 */
object VoiceAccessAutomationState {

    data class Snapshot(
        val notificationAccessEnabled: Boolean = false,
        val hostRunning: Boolean = false,
        val listenerConnected: Boolean = false,
        val shizukuReady: Boolean = false,
        val userServiceConnected: Boolean = false,
        val voiceAccessUidFound: Boolean = false,
        val watcherRegistered: Boolean = false,
        val recordAudioActive: Boolean = false,
        val autoRoutingActive: Boolean = false,
        val lastMessage: String = "Automation has not started yet."
    )

    private val _state =
        MutableStateFlow(
            Snapshot()
        )

    val state: StateFlow<Snapshot> =
        _state.asStateFlow()

    @Synchronized
    fun update(
        transform: (Snapshot) -> Snapshot
    ) {
        _state.value =
            transform(
                _state.value
            )
    }
}
