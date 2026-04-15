// IPrivilegedService.aidl
// AIDL interface for the Shizuku UserService that runs with shell privileges.
package com.btmicfix;

interface IPrivilegedService {
    // Required by Shizuku for service lifecycle management
    void destroy() = 16777114;

    // Execute an audio routing command as shell user
    String executeAudioCommand(String command) = 1;

    // Get audio system diagnostics
    String getAudioDump() = 2;

    // Force set preferred device for audio strategy (system API)
    boolean forceAudioStrategy(int strategy, int deviceType) = 3;
}
