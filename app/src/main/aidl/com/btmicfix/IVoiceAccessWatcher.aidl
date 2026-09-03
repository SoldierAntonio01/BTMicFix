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
     * Reserved Shizuku UserService destroy transaction.
     */
    void destroy() = 16777114;
}
