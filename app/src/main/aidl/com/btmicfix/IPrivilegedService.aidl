package com.btmicfix;

interface IPrivilegedService {

    void destroy();

    String executeAudioCommand(String command);

    String getAudioDump();

    boolean forceAudioStrategy(
        int strategy,
        int deviceType
    );

    String forceLeAudio(
        String address
    );
}
