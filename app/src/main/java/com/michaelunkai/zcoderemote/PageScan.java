package com.michaelunkai.zcoderemote;

import android.webkit.WebView;

/**
 * The one page-health scanner used by every surface: MainActivity's resumed
 * 5 s scan AND the holder's 30 s offscreen scan run this same probe, so both
 * paths classify the page identically. Results feed the overlay logic AND the
 * process-wide SessionHealth state that the PC-side watchdog polls through
 * the bridge /status endpoint.
 */
public final class PageScan {

    /** States the probe can return (SessionHealth vocabulary). */
    public static final String OK = "ok";
    public static final String CONNECTING = "connecting";
    public static final String INVALID = "invalid";
    public static final String MENU = "menu";
    public static final String DESKTOP_OFFLINE = "desktop_offline";
    /**
     * "This phone reached the relay, but desktop did not return workspace
     * data in time" (DesktopTimedOut, live-seen 2026-10-25): the relay is
     * fine, the cloud-side terminal provisioning is stuck or slow. This is
     * a WAIT state — the phone keeps auto-retrying the same link and the
     * watchdog must not mint (the link is alive; the terminal is not).
     */
    public static final String DESKTOP_TIMEDOUT = "desktop_timedout";
    /**
     * The page is NOT the live session chat: error shell, login redirect,
     * blank skeleton — anything without the chat composer on the session
     * path. "ok" is a POSITIVE classification (session URL + composer), so
     * unknown failure pages can never masquerade as healthy.
     */
    public static final String NOSESSION = "nosession";

    /**
     * Scans the loaded session page for failure markers. The rotation-invalid
     * page, the hung relay wizard, the desktop-offline page and the orphaned
     * sessions-menu fallback all render LONG AFTER onPageFinished, so this
     * must run periodically, not just once per load. A page only counts as
     * "ok" when it still sits on the /remote session path AND shows the chat
     * composer; everything else is a healable non-session state.
     */
    public static final String JS =
            "(function(){var t=(document.body&&document.body.innerText)||'';"
            + "if(t.indexOf('Connection Invalid')>=0||t.indexOf('AUTH_FAILED')>=0)return 'invalid';"
            + "if(t.indexOf('Connecting relay service')>=0)return 'connecting';"
            // Desktop side disconnected mid-pairing or after it ended
            // ("Desktop offline"/DEVICE_OFFLINE page with its own Try Again).
            + "if(t.indexOf('DEVICE_OFFLINE')>=0||t.indexOf('Desktop offline')>=0)return 'desktop_offline';"
            // Relay ok, cloud terminal not serving workspace data yet — wait.
            + "if(t.indexOf('DesktopTimedOut')>=0"
            + "||t.indexOf('did not return workspace data')>=0)return 'desktop_timedout';"
            // Redirected off the session path (login, error shell): dead link.
            + "if(location.pathname.indexOf('/remote/')!==0)return 'nosession';"
            // Orphaned-link state: the linked desktop session ended and the
            // web app silently drops the phone into its sessions/tasks menu
            // (drawer markers present) with no chat composer anywhere. The
            // SPA path rides along after "|" for diagnosis ("menu|/" vs
            // "nosession|/auth/...").
            + "var composer=document.querySelector('textarea,[contenteditable=\"true\"],[contenteditable=\"\"]');"
            + "if(!composer){var nav=0;"
            + "if(t.indexOf('Pinned tasks')>=0)nav++;"
            + "if(t.indexOf('Automations')>=0)nav++;"
            + "if(t.indexOf('Plugin Marketplace')>=0)nav++;"
            + "if(t.indexOf('Task list')>=0)nav++;"
            // Path + first words of the page body ride along after "|" for
            // live diagnosis ("nosession|/remote/v4|Waiting for desktop
            // pairing" — the watchdog and logcat see exactly what the user
            // would see, without any screenshot round-trip.
            + "var head=t.replace(/\\s+/g,' ').trim().slice(0,400);"
            + "if(nav>=2)return 'menu|'+location.pathname+'|'+head;"
            + "return 'nosession|'+location.pathname+'|'+head;}"
            + "return 'ok';})();";

    private PageScan() {
    }

    /** Runs the probe and reports the classified state plus its diagnostic
     *  detail (the SPA path for non-session states, "" otherwise). */
    public static void scan(WebView view, Callback callback) {
        if (view == null) {
            return;
        }
        view.evaluateJavascript(JS, value -> {
            if (callback != null) {
                String raw = value == null ? "" : value.replace("\"", "").trim();
                int split = raw.indexOf('|');
                String state = split < 0 ? raw : raw.substring(0, split);
                String detail = split < 0 ? "" : raw.substring(split + 1);
                callback.onState(classify("\"" + state + "\""), detail);
            }
        });
    }

    public interface Callback {
        void onState(String state, String detail);
    }

    /**
     * Maps the raw evaluateJavascript result to a state name (the "|path"
     * diagnostic suffix is split away). Pure String logic — safe for JVM
     * unit tests.
     */
    public static String classify(String value) {
        if (value == null) {
            return "";
        }
        String v = value.replace("\"", "").trim();
        int pipe = v.indexOf('|');
        if (pipe >= 0) {
            v = v.substring(0, pipe);
        }
        if (INVALID.equals(v) || CONNECTING.equals(v)
                || MENU.equals(v) || DESKTOP_OFFLINE.equals(v)
                || DESKTOP_TIMEDOUT.equals(v)
                || NOSESSION.equals(v) || OK.equals(v)) {
            return v;
        }
        return "";
    }
}
