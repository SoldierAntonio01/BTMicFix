package com.btmicfix.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide diagnostics for Voice Access automation.
 *
 * This lets HomeScreen show exactly which part is working
 * instead of us guessing from screenshots.
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
