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

    /*
     * Reserved Shizuku UserService destroy transaction.
     */
    void destroy() = 16777114;
}
