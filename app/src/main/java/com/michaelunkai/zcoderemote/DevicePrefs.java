package com.michaelunkai.zcoderemote;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Small typed access to the device-feature preferences (keep-alive, hotword,
 * Wake-on-LAN target). One SharedPreferences file, read everywhere, written
 * from the device panel.
 */
public final class DevicePrefs {

    public static final String PREFS_FILE = "zcode_device";

    private static final String KEY_KEEP_ALIVE = "keep_alive_enabled";
    private static final String KEY_HOTWORD = "hotword_enabled";
    private static final String KEY_WOL_MAC = "wol_mac";
    private static final String KEY_WOL_TARGET = "wol_target";

    private DevicePrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    public static boolean isKeepAliveEnabled(Context context) {
        return prefs(context).getBoolean(KEY_KEEP_ALIVE, true);
    }

    public static void setKeepAliveEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_KEEP_ALIVE, enabled).apply();
    }

    public static boolean isHotwordEnabled(Context context) {
        return prefs(context).getBoolean(KEY_HOTWORD, true);
    }

    public static void setHotwordEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_HOTWORD, enabled).apply();
    }

    public static String getWakeMac(Context context) {
        return prefs(context).getString(KEY_WOL_MAC, "");
    }

    public static void setWakeMac(Context context, String mac) {
        prefs(context).edit().putString(KEY_WOL_MAC, mac == null ? "" : mac.trim()).apply();
    }

    public static String getWakeTarget(Context context) {
        return prefs(context).getString(KEY_WOL_TARGET, "255.255.255.255:9");
    }

    public static void setWakeTarget(Context context, String target) {
        prefs(context).edit().putString(KEY_WOL_TARGET, target == null ? "" : target.trim()).apply();
    }

    /** "host:port" -> port, defaults to 9 (WoL discard port) when absent/invalid. */
    public static int wakePort(Context context) {
        String target = getWakeTarget(context);
        int idx = target.lastIndexOf(':');
        if (idx > 0) {
            try {
                return Integer.parseInt(target.substring(idx + 1));
            } catch (NumberFormatException e) {
                return 9;
            }
        }
        return 9;
    }

    /** "host:port" -> host part. */
    public static String wakeHost(Context context) {
        String target = getWakeTarget(context);
        int idx = target.lastIndexOf(':');
        return idx > 0 ? target.substring(0, idx) : target;
    }
}
