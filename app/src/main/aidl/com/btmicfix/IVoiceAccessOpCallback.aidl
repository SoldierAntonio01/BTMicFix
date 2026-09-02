package com.btmicfix;

interface IVoiceAccessOpCallback {

    oneway void onRecordAudioActiveChanged(
        boolean active
    );
}
