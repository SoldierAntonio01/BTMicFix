package com.btmicfix;

interface IVoiceAccessOpCallback {

    /**
     * Fired when Voice Access attempts to start RECORD_AUDIO.
     *
     * BTMicFix uses this as an early signal to select the already
     * connected BLE communication device.
     */
    oneway void onRecordAudioStarting();

    /**
     * Fired when Voice Access RECORD_AUDIO becomes active/inactive.
     */
    oneway void onRecordAudioActiveChanged(
        boolean active
    );

    /**
     * Fired when the privileged Shizuku process applies or clears
     * the preferred microphone device for the Voice Access capture preset.
     *
     * audioSource is a MediaRecorder.AudioSource integer.
     */
    oneway void onCaptureRoutingChanged(
        boolean applied,
        int audioSource
    );
}
