package com.michaelunkai.zcoderemote;

import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;

/**
 * Runs when the user triggers ZCode through the system assistant path
 * (assistant gesture / long-press power after selecting ZCode as the digital
 * assistant app). Immediately opens the live session and closes the session
 * UI — zero delay, zero battery when idle.
 */
public class ZcodeVoiceInteractionSession extends VoiceInteractionSession {

    public ZcodeVoiceInteractionSession(android.content.Context context) {
        super(context);
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        SessionLauncher.openSession(getContext());
        finish();
    }
}
