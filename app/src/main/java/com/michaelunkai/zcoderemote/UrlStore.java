package com.michaelunkai.zcoderemote;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.URI;
import java.util.Locale;

/**
 * Stores the remote session URL in SharedPreferences with a BuildConfig-provided default fallback.
 * Every saved value passes through {@link #normalize(String)} so stored URLs are absolute http/https.
 */
public class UrlStore {

    private static final String PREFS_FILE = "zcode_remote_prefs";
    private static final String KEY_REMOTE_URL = "remote_url";

    private final SharedPreferences prefs;

    public UrlStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    public String get() {
        String stored = prefs.getString(KEY_REMOTE_URL, null);
        if (stored != null && !stored.trim().isEmpty()) {
            return stored.trim();
        }
        String fallback = BuildConfig.DEFAULT_REMOTE_URL;
        return fallback == null ? "" : fallback.trim();
    }

    public boolean save(String raw) {
        String normalized = normalize(raw);
        if (normalized == null) {
            return false;
        }
        prefs.edit().putString(KEY_REMOTE_URL, normalized).apply();
        return true;
    }

    public void reset() {
        prefs.edit().remove(KEY_REMOTE_URL).apply();
    }

    public boolean isUsingDefault() {
        String stored = prefs.getString(KEY_REMOTE_URL, null);
        return stored == null || stored.trim().isEmpty();
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String candidate = raw.trim();
        if (candidate.isEmpty()) {
            return null;
        }
        if (!candidate.regionMatches(true, 0, "http://", 0, 7)
                && !candidate.regionMatches(true, 0, "https://", 0, 8)) {
            candidate = "https://" + candidate;
        }
        URI uri;
        try {
            uri = URI.create(candidate);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (("http".equals(scheme) || "https".equals(scheme))
                && host != null
                && host.contains(".")) {
            return candidate;
        }
        return null;
    }
}
