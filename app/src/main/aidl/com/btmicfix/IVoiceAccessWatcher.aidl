package com.btmicfix;

import com.btmicfix.IVoiceAccessOpCallback;

interface IVoiceAccessWatcher {

    String startWatch(
        int targetUid,
        String targetPackage,
        IVoiceAccessOpCallback callback
    ) = 1;

    void stopWatch() = 2;

    boolean isTargetActive() = 3;

    String getStatus() = 4;

    void destroy() = 16777114;
}
