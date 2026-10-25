package com.michaelunkai.zcoderemote;

import android.util.Log;

/**
 * Process-wide holder of the newest ProjectsBar page scan. Logcat carries a
 * truncated copy so the PC agent can verify the overlay works live on the
 * session page without any extra network surface.
 */
public final class WebScan {

    private static final Object LOCK = new Object();
    private static volatile String last = "";

    private WebScan() {
    }

    public static void set(String json) {
        last = json == null ? "" : json;
        synchronized (LOCK) {
            String line = last;
            for (int i = 0; i < line.length(); i += 3500) {
                Log.i(ProjectsBar.TAG, "scan[" + (i / 3500) + "] "
                        + line.substring(i, Math.min(line.length(), i + 3500)));
            }
        }
    }

    public static String get() {
        return last;
    }
}
