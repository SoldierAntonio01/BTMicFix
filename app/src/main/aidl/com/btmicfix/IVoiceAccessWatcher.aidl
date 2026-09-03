package com.btmicfix;

import com.btmicfix.IVoiceAccessOpCallback;

interface IVoiceAccessWatcher {

    /**
     * Start watching Google's Voice Access RECORD_AUDIO app-op.
     *
     * bleInputDeviceType is normally AudioDeviceInfo.TYPE_BLE_HEADSET.
     * bleInputAddress is passed internally only and is not displayed.
     */
    String startWatch(
        int targetUid,
        String targetPackage,
        int bleInputDeviceType,
        String bleInputAddress,
        IVoiceAccessOpCallback callback
    ) = 1;

    void stopWatch() = 2;

    boolean isTargetActive() = 3;

    String getStatus() = 4;

    /**
     * Removes any temporary capture-preset preference installed
     * by BTMicFix.
     */
    void clearCapturePreference() = 5;

    /**
     * Update the actual connected BLE INPUT endpoint.
     *
     * This must come from AudioManager.GET_DEVICES_INPUTS, not from
     * availableCommunicationDevices (which contains communication sinks).
     */
    String updateBleInputTarget(
        int bleInputDeviceType,
        String bleInputAddress
    ) = 6;

    /**
     * Re-detect Voice Access's active capture preset and apply the
     * currently supplied BLE input to it.
     */
    boolean refreshCapturePreference() = 7;

    /**
     * Reserved Shizuku UserService destroy transaction.
     */
    void destroy() = 16777114;
}
