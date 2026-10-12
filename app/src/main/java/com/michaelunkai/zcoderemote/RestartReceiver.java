package com.michaelunkai.zcoderemote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Brings the keep-alive process back after a reboot or an app update, so the
 * next icon tap is a warm resume instead of a cold page handshake. If the OS
 * blocks a foreground-service start at boot (policy varies by version), the
 * failure is harmless — the first manual open recovers exactly as before.
 */
public class RestartReceiver extends BroadcastReceiver {

    private static final String TAG = "ZcodeRestart";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        try {
            SessionKeepService.start(context);
            Log.i(TAG, "keep-alive restored after " + action);
        } catch (Throwable t) {
            Log.w(TAG, "boot restore blocked by the system; first open recovers", t);
        }
    }
}
