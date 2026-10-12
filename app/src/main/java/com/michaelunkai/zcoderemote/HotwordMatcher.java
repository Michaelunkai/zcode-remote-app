package com.michaelunkai.zcoderemote;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides whether a speech-recognizer result (partial or final) contains the
 * "hey zcode" wake phrase. Recognizers mangle the made-up brand ("z coat",
 * "z cod", "z code"), so matching is deliberately tolerant while staying
 * strict enough to never fire on ordinary speech like "open the code".
 * Pure JVM — unit tested.
 */
public final class HotwordMatcher {

    private static final Pattern WAKE_PATTERN = Pattern.compile(
            "\\bz\\s*(code|cod|coat|cote|koad|kote|cort)");

    private HotwordMatcher() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static boolean matches(String raw) {
        String text = normalize(raw);
        if (text.isEmpty()) {
            return false;
        }
        if (WAKE_PATTERN.matcher(text).find()) {
            return true;
        }
        return text.contains("zcode") || text.contains("z code");
    }
}
