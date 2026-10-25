package com.michaelunkai.zcoderemote;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

/**
 * One UI strips our accessibility service from enabled_accessibility_services
 * on every app update and every reboot (owner-verified twice). The app holds
 * WRITE_SECURE_SETTINGS (granted over adb), so it can re-merge itself without
 * root: read the current list, append our flat component if missing, and keep
 * accessibility_enabled=1. Never drops existing entries of other apps.
 */
public final class AccessibilityHealer {

    private static final String TAG = "ZcodeHeal";
    private static final String FLAT =
            "com.michaelunkai.zcoderemote/"
            + "com.michaelunkai.zcoderemote.ZcodeControlAccessibilityService";

    private AccessibilityHealer() {}

    /** Re-merges the control service into the enabled set. Returns true when enabled. */
    public static boolean heal(Context context) {
        try {
            ContentResolver cr = context.getContentResolver();
            String current = Settings.Secure.getString(
                    cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (current == null) current = "";

            boolean present = false;
            for (String entry : current.split(":")) {
                if (FLAT.equals(entry.trim())) { present = true; break; }
            }

            int enabled = Settings.Secure.getInt(
                    cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0);

            if (present && enabled == 1) return true;

            StringBuilder merged = new StringBuilder(current);
            if (merged.length() > 0 && merged.charAt(merged.length() - 1) != ':') {
                merged.append(':');
            }
            merged.append(FLAT);

            boolean ok = Settings.Secure.putString(
                    cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    merged.toString());
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Log.i(TAG, "heal applied: merged=true ok=" + ok + " prevEnabled=" + enabled);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "heal failed (WRITE_SECURE_SETTINGS missing?)", t);
            return false;
        }
    }
}
