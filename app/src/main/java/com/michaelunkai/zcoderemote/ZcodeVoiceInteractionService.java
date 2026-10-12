package com.michaelunkai.zcoderemote;

import android.service.voice.VoiceInteractionService;

/**
 * Registers ZCode as a digital-assistant-capable app. When the user selects it
 * as the default assistant, the system assistant trigger (assistant gesture /
 * long-press power / corner swipe) opens the session instantly — a zero-battery
 * launch path that works even while the screen is locked. The wake phrase
 * itself is handled by HotwordService; the platform's DSP keyphrase enrollment
 * (AlwaysOnHotwordDetector) is hidden API on retail devices, so it is not used.
 */
public class ZcodeVoiceInteractionService extends VoiceInteractionService {

    @Override
    public void onReady() {
        super.onReady();
        // Nothing to arm: the assistant trigger is wired purely through the manifest.
    }
}
