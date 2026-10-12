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
    private static final String KEY_WOL_MAC = "wol_mac";
    private static final String KEY_WOL_TARGET = "wol_target";
    private static final String KEY_BRIDGE_ENABLED = "bridge_enabled";
    private static final String KEY_BRIDGE_TOKEN = "bridge_token";
    private static final String KEY_BRIDGE_PORT = "bridge_port";

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

    public static boolean isBridgeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_BRIDGE_ENABLED, true);
    }

    public static void setBridgeEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_BRIDGE_ENABLED, enabled).apply();
    }

    /** 32-hex-char bearer token, generated once on first use. */
    public static String getBridgeToken(Context context) {
        String token = prefs(context).getString(KEY_BRIDGE_TOKEN, null);
        if (token == null || token.length() != 32) {
            java.security.SecureRandom random = new java.security.SecureRandom();
            StringBuilder builder = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                builder.append(String.format("%02x", random.nextInt(256)));
            }
            token = builder.toString();
            prefs(context).edit().putString(KEY_BRIDGE_TOKEN, token).apply();
        }
        return token;
    }

    public static int getBridgePort(Context context) {
        return prefs(context).getInt(KEY_BRIDGE_PORT, 8790);
    }

    public static void setBridgePort(Context context, int port) {
        prefs(context).edit().putInt(KEY_BRIDGE_PORT, port).apply();
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
