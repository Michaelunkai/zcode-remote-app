package com.michaelunkai.zcoderemote;

import android.os.SystemClock;
import android.util.Log;

/**
 * Process-wide page-health state. Every scan (resumed 5 s loop, offscreen
 * 30 s loop, load/start/error transitions) records the raw classification
 * here, and the agent bridge serves it from /status so the PC-side watchdog
 * can see the session's real state without the app ever being opened.
 */
public final class SessionHealth {

    private static final String TAG = "ZcodeHealth";

    /** Set for non-scan situations so the watchdog can tell them apart. */
    public static final String LOADING = "loading";
    public static final String ERROR = "error";
    public static final String LOCAL_AGENT = "local_agent";

    private static volatile String state = "";
    private static volatile String detail = "";
    private static volatile long stateAt = 0L;
    private static volatile long loadStartedAt = 0L;

    private SessionHealth() {
    }

    /** Diagnostic detail for the current state (SPA path of a dead page). */
    public static void setDetail(String newDetail) {
        detail = newDetail == null ? "" : newDetail;
    }

    public static String detail() {
        return detail;
    }

    /**
     * Records a classified page state (PageScan vocabulary). Identical
     * consecutive readings do NOT refresh the timestamp: ageSeconds() then
     * reports how long the page has been stuck in the SAME state, which is
     * what the watchdog's persistence thresholds key on.
     */
    public static void set(String newState) {
        if (newState == null || newState.isEmpty()) {
            return;
        }
        if (newState.equals(state)) {
            return;
        }
        state = newState;
        stateAt = SystemClock.elapsedRealtime();
    }

    /** Marks a fresh session load (the watchdog ignores transient states early in a load). */
    public static void loadStarted() {
        loadStartedAt = SystemClock.elapsedRealtime();
        set(LOADING);
    }

    public static String state() {
        return state;
    }

    /** Seconds since the state was recorded; -1 when never set. */
    public static long ageSeconds() {
        if (stateAt == 0L) {
            return -1;
        }
        return (SystemClock.elapsedRealtime() - stateAt) / 1000L;
    }

    /** Seconds since the last load started; -1 when never. */
    public static long sinceLoadSeconds() {
        if (loadStartedAt == 0L) {
            return -1;
        }
        return (SystemClock.elapsedRealtime() - loadStartedAt) / 1000L;
    }

    /** Logcat breadcrumb so live behavior is provable without the bridge. */
    public static void log(String state) {
        Log.i(TAG, "page=" + state + (detail.isEmpty() ? "" : " at " + detail)
                + " age=" + ageSeconds() + "s");
    }
}
