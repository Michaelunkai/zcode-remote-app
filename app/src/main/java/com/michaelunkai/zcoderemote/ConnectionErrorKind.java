package com.michaelunkai.zcoderemote;

/**
 * Classifies a WebView main-frame error description into the offline-mode
 * behavior the app owes the user. JVM-pure (string logic only) so unit tests
 * never touch android.* — same rule as UrlStore.
 */
public enum ConnectionErrorKind {

    /** The PC is unreachable (off, dead link host, no route): offline mode. */
    UNREACHABLE,

    /** Any other load failure: plain error overlay. */
    OTHER;

    /**
     * Lowercase substring markers covering both Chromium engine descriptions
     * (net::ERR_*) and Android framework wording ("The connection to the
     * server was unsuccessful."). Never throws; null or unknown → OTHER.
     */
    public static ConnectionErrorKind classify(String description) {
        if (description == null) {
            return OTHER;
        }
        String text = description.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        String[] markers = {
                "connection refused",
                "timed out",
                "internet disconnected",
                "name not resolved",
                "address unreachable",
                "unknown host",
                "unknownhostexception",
                "unable to resolve host",
                "no address associated",
                "network is unreachable",
                "connection reset",
                "connection to the server was unsuccessful",
                "failed to connect"
        };
        for (String marker : markers) {
            if (text.contains(marker)) {
                return UNREACHABLE;
            }
        }
        return OTHER;
    }
}
