package com.michaelunkai.zcoderemote;

import android.os.Bundle;
import android.service.voice.VoiceInteractionSessionService;

public class ZcodeVoiceInteractionSessionService extends VoiceInteractionSessionService {

    @Override
    public ZcodeVoiceInteractionSession onNewSession(Bundle args) {
        return new ZcodeVoiceInteractionSession(this);
    }
}
