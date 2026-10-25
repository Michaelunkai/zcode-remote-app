package com.michaelunkai.zcoderemote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import java.net.URI;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * JS bridge exposed to the loaded session page as {@code window.ZcodeAndroid}.
 * Lets the live session drive the phone (same powers as the device panel).
 * Every call is origin-gated on the main thread: the current WebView URL host
 * must match the host of the configured session URL, otherwise the call is
 * dropped with a toast.
 */
public class ZcodeBridge {

    private final Context context;
    private final Supplier<String> currentPageUrl;
    private final Handler main = new Handler(Looper.getMainLooper());

    public ZcodeBridge(Context context, Supplier<String> currentPageUrl) {
        this.context = context.getApplicationContext();
        this.currentPageUrl = currentPageUrl;
    }

    /** Liveness probe for the page. */
    @JavascriptInterface
    public String ping() {
        return "zcode-android-ok";
    }

    /**
     * ProjectsBar self-reporting: the injected quick-bar sends what it sees
     * (visible menu structure, no URL, no message content beyond short menu
     * labels) so the PC agent can verify the archive automation over logcat.
     */
    @JavascriptInterface
    public void reportScan(String json) {
        String scan = json == null ? "" : json;
        if (scan.length() > 120000) {
            scan = scan.substring(0, 120000);
        }
        WebScan.set(scan);
    }

    /** where: back|home|recents|notifications|quickSettings|power|lock */
    @JavascriptInterface
    public void nav(String where) {
        secure(() -> {
            Integer action = mapNav(where);
            if (action == null) {
                reject("unknown nav target");
                return;
            }
            if (action == DeviceActions.GLOBAL_LOCK_SCREEN) {
                DeviceActions.lockScreen(context, DevicePanelActivity.toastFeedback(context));
            } else {
                DeviceActions.globalAction(context, action,
                        DevicePanelActivity.toastFeedback(context));
            }
        });
    }

    @JavascriptInterface
    public void tap(int x, int y) {
        secure(() -> DeviceActions.tap(context, x, y, DevicePanelActivity.toastFeedback(context)));
    }

    @JavascriptInterface
    public void longPress(int x, int y) {
        secure(() -> DeviceActions.longPress(context, x, y,
                DevicePanelActivity.toastFeedback(context)));
    }

    @JavascriptInterface
    public void swipe(int x1, int y1, int x2, int y2, int durationMs) {
        secure(() -> DeviceActions.swipe(context, x1, y1, x2, y2, durationMs,
                DevicePanelActivity.toastFeedback(context)));
    }

    @JavascriptInterface
    public void typeText(String text) {
        secure(() -> DeviceActions.typeText(context, text == null ? "" : text,
                DevicePanelActivity.toastFeedback(context)));
    }

    @JavascriptInterface
    public void screenshot() {
        secure(() -> DeviceActions.screenshot(context,
                DevicePanelActivity.toastFeedback(context)));
    }

    @JavascriptInterface
    public void launchApp(String packageName) {
        secure(() -> DeviceActions.launchApp(context, packageName == null ? "" : packageName.trim(),
                DevicePanelActivity.toastFeedback(context)));
    }

    /** dir: up|down|mute|unmute */
    @JavascriptInterface
    public void volume(String dir) {
        secure(() -> DeviceActions.volume(context, dir,
                DevicePanelActivity.toastFeedback(context)));
    }

    /** key: play|next|prev */
    @JavascriptInterface
    public void media(String key) {
        secure(() -> DeviceActions.mediaKey(context, key,
                DevicePanelActivity.toastFeedback(context)));
    }

    @JavascriptInterface
    public void torch(boolean on) {
        secure(() -> DeviceActions.torch(context, on,
                DevicePanelActivity.toastFeedback(context)));
    }

    @JavascriptInterface
    public void setBrightness(int value) {
        secure(() -> DeviceActions.setBrightness(context, value,
                DevicePanelActivity.toastFeedback(context)));
    }

    @JavascriptInterface
    public void wakePc() {
        secure(() -> DeviceActions.wakePc(context,
                DevicePanelActivity.toastFeedback(context)));
    }

    /** Runs the action on the main thread only when the page origin is trusted. */
    private void secure(Runnable action) {
        main.post(() -> {
            if (!originAllowed()) {
                reject(context.getString(R.string.bridge_rejected));
                return;
            }
            action.run();
        });
    }

    /** Main thread only: page host must equal the configured session host. */
    private boolean originAllowed() {
        String page = currentPageUrl.get();
        String pageHost = hostOf(page);
        String allowedHost = hostOf(new UrlStore(context).get());
        return pageHost != null && pageHost.equalsIgnoreCase(allowedHost);
    }

    private static String hostOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            return new URI(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private Integer mapNav(String where) {
        if (where == null) {
            return null;
        }
        switch (where.toLowerCase(Locale.ROOT)) {
            case "back":
                return DeviceActions.GLOBAL_BACK;
            case "home":
                return DeviceActions.GLOBAL_HOME;
            case "recents":
                return DeviceActions.GLOBAL_RECENTS;
            case "notifications":
                return DeviceActions.GLOBAL_NOTIFICATIONS;
            case "quicksettings":
                return DeviceActions.GLOBAL_QUICK_SETTINGS;
            case "power":
                return DeviceActions.GLOBAL_POWER_DIALOG;
            case "lock":
                return DeviceActions.GLOBAL_LOCK_SCREEN;
            default:
                return null;
        }
    }

    private void reject(String reason) {
        main.post(() -> Toast.makeText(context, reason, Toast.LENGTH_SHORT).show());
    }
}
