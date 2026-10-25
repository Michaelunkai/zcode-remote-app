package com.michaelunkai.zcoderemote;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Process-wide owner of the ONE live session WebView. The keep-alive service
 * prewarms it at boot (offscreen, page already loading/connecting), and
 * MainActivity adopts the very same view on open — so the user never waits
 * for the ~9 s server-side relay handshake on first open after a reboot or
 * process kill: the page is already connected and just needs attaching
 * (attach + resume measures in the low hundreds of milliseconds).
 *
 * Lifecycle contract:
 * - ensureLoaded(): service creates/loads the offscreen view when idle.
 * - adopt(): activity detaches it from any parent and parents it into its
 *   own layout; the holder keeps a reference so the view is never destroyed
 *   with the activity.
 * - handBack(): activity detaches on destroy WITHOUT webView.destroy() —
 *   the page keeps running (same process, held by the foreground service).
 */
public final class SessionWebViewHolder {

    private static final String TAG = "ZcodePrewarm";

    private static WebView webView;
    private static String loadedUrl = "";
    private static boolean attached = false;   // currently parented into an activity
    private static boolean destroyed = false;  // renderer/view unusable
    private static boolean inOverlay = false;  // pinned in the offscreen keep-visible window
    private static long desktopOfflineSince = 0L; // 0 = not currently desktop-offline
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * Offscreen health loop (~30 s): while the session page idles in the
     * overlay — app closed, screen off, phone locked — the same PageScan the
     * resumed activity runs classifies the page and records it in
     * SessionHealth, so the PC-side watchdog sees a dead session (and heals
     * it) long before the user ever opens the app. Desktop-offline is the one
     * state the phone heals itself: a reload once it has persisted >20 s
     * reconnects the moment the desktop's remote side is back.
     */
    private static final Runnable idleHealthScan = new Runnable() {
        @Override
        public void run() {
            if (webView == null || destroyed || attached) {
                return;
            }
            try {
                PageScan.scan(webView, (state, detail) -> {
                    if (state == null || state.isEmpty()) {
                        return;
                    }
                    SessionHealth.set(state);
                    SessionHealth.setDetail(detail);
                    SessionHealth.log(state);
                    if (PageScan.DESKTOP_OFFLINE.equals(state)
                            || PageScan.DESKTOP_TIMEDOUT.equals(state)) {
                        // Background self-heal: tap the SPA's own Try Again
                        // so an offscreen page recovers without being opened.
                        webView.evaluateJavascript(
                                "(function(){var bs=document.querySelectorAll"
                                + "('button,[role=button]');for(var i=0;i<bs.length;i++)"
                                + "{var t=((bs[i].innerText||'')+' '+(bs[i]"
                                + ".getAttribute('aria-label')||''));"
                                + "if(/try again/i.test(t)){try{bs[i].click();}"
                                + "catch(e){}return;}}})();", null);
                        long now = SystemClock.elapsedRealtime();
                        if (desktopOfflineSince == 0L) {
                            desktopOfflineSince = now;
                        } else if (now - desktopOfflineSince > 75000L) {
                            desktopOfflineSince = 0L;
                            String url = loadedUrl;
                            if (!url.isEmpty()) {
                                Log.i(TAG, "offscreen terminal not serving >20s; reloading idle page");
                                reloadIfIdle(url);
                            }
                        }
                    } else {
                        desktopOfflineSince = 0L;
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "idle health scan failed", t);
            }
            MAIN.postDelayed(this, 30000L);
        }
    };

    /**
     * Injected on every load: the SPA pauses its relay heartbeat when it
     * believes the page is hidden (document.hidden / visibilityState), and
     * Android flips those on every backgrounding and screen-off. Spoof the
     * getters so the page always reports visible — combined with the
     * offscreen overlay pin below, the session survives background and lock.
     */
    public static final String VISIBILITY_JS =
            "(function(){try{"
            + "Object.defineProperty(document,'hidden',{get:function(){return false;},configurable:true});"
            + "Object.defineProperty(document,'visibilityState',{get:function(){return 'visible';},configurable:true});"
            + "}catch(e){}})();";

    private SessionWebViewHolder() {
    }

    /** The URL currently loaded (or loading) into the shared view; "" when none. */
    public static String loadedUrl() {
        return destroyed ? "" : loadedUrl;
    }

    /** True when the shared view exists and is not parented to an activity. */
    public static boolean hasSpareView() {
        return webView != null && !attached && !destroyed;
    }

    /**
     * True while an activity currently owns the shared view (onStart..onStop):
     * the only reliable "user is looking at the page" signal — accessibility
     * foreground tracking can lag a Home press by events.
     */
    public static boolean isPageAttached() {
        return webView != null && !destroyed && attached;
    }

    /**
     * Service entry point: make sure an offscreen WebView exists and is
     * loading/holding the given session URL. Cheap no-op when the activity
     * already owns the view with the same URL.
     */
    @SuppressLint("SetJavaScriptEnabled")
    public static void ensureLoaded(Context appContext, String url) {
        if (appContext == null || url == null || url.isEmpty()) {
            return;
        }
        if (attached) {
            Log.i(TAG, "ensureLoaded: skipped, view attached to activity"); // the live activity owns the view and manages its own URLs
            return;
        }
        if (webView != null && !destroyed && url.equals(loadedUrl)) {
            Log.i(TAG, "ensureLoaded: idle view already holds this url"); // offscreen view already holds this page; nothing to do
            return;
        }
        Log.i(TAG, "ensureLoaded: (re)loading idle view");
        MAIN.post(() -> createAndLoad(appContext.getApplicationContext(), url));
    }

    private static void createAndLoad(Context app, String url) {
        if (attached) {
            Log.i(TAG, "createAndLoad: skipped, view became attached");
            return;
        }
        if (webView != null && !destroyed) {
            if (url.equals(loadedUrl)) {
                return;
            }
            // Different URL (rotation healed from the PC): reload in place —
            // keeps cookies/tabs state and avoids a second WebView instance.
            Log.i(TAG, "createAndLoad: reloading idle view with new url");
            loadedUrl = url;
            SessionHealth.loadStarted();
            webView.loadUrl(url);
            return;
        }
        try {
            WebView view = new WebView(app);
            WebSettings settings = view.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
            settings.setSupportZoom(false);
            settings.setBuiltInZoomControls(false);
            settings.setDisplayZoomControls(false);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            settings.setDefaultTextEncodingName("utf-8");
            settings.setCacheMode(WebSettings.LOAD_DEFAULT);
            view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
            view.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
            view.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageStarted(WebView v, String pageUrl, android.graphics.Bitmap favicon) {
                    Log.i(TAG, "idle page started loading");
                }

                @Override
                public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                    removeFromOverlay();
                    destroyed = true;
                    webView = null;
                    loadedUrl = "";
                    Log.w(TAG, "prewarmed renderer died; will re-prewarm on next ensureLoaded");
                    MAIN.postDelayed(() -> ensureLoaded(app, url), 3000L);
                    return true;
                }

                @Override
                public void onPageFinished(WebView v, String pageUrl) {
                    v.evaluateJavascript(VISIBILITY_JS, null);
                    Log.i(TAG, "prewarmed page finished: " + (pageUrl == null ? "?" : "ok"));
                }
            });
            webView = view;
            destroyed = false;
            attached = false;
            loadedUrl = url;
            SessionHealth.loadStarted();
            view.loadUrl(url);
            Log.i(TAG, "offscreen session WebView prewarming " + url.split("\\?")[0]);
        } catch (Throwable t) {
            Log.w(TAG, "prewarm failed; first open recovers as before", t);
        }
    }

    /**
     * Activity entry point: take the shared view (never destroyed here) and
     * parent it into the caller's SwipeRefresh container. Caller must
     * re-install its WebViewClient/WebChromeClient + JS interfaces after
     * adopting. Returns null when no usable shared view exists (caller
     * creates its own and registers it via register()).
     */
    public static WebView adopt() {
        if (webView == null || destroyed) {
            return null;
        }
        removeFromOverlay();
        ViewGroup parent = (ViewGroup) webView.getParent();
        if (parent != null) {
            parent.removeView(webView);
        }
        attached = true;
        return webView;
    }

    /**
     * Activity registers its freshly created view as the shared one so the
     * service keeps it alive after the activity goes away. Any idle view it
     * replaces (e.g. after a URL change made it obsolete) is destroyed.
     */
    public static void register(WebView view, String url) {
        if (view == null) {
            return;
        }
        if (webView != null && webView != view && !destroyed && !attached) {
            removeFromOverlay();
            try {
                webView.destroy();
            } catch (Throwable ignored) {
            }
        }
        webView = view;
        loadedUrl = url == null ? "" : url;
        destroyed = false;
        attached = true;
    }

    /**
     * Activity hands the view back on destroy: detach from the activity
     * layout WITHOUT destroying — the page keeps running offscreen in the
     * foreground-service process for instant next-open.
     */
    public static void handBack() {
        if (webView == null || destroyed) {
            return;
        }
        removeFromOverlay();
        ViewGroup parent = (ViewGroup) webView.getParent();
        if (parent != null) {
            parent.removeView(webView);
        }
        attached = false;
        keepVisibleInOverlay();
    }

    /**
     * Activity is stopping (screen off, home): move the session view into the
     * offscreen overlay so Chromium keeps counting the page VISIBLE while the
     * activity's own window is hidden — without this, a stopped activity
     * hides the page and the relay heartbeat throttles to death. The activity
     * re-parents the same view in onStart (its layout slot was recorded).
     */
    public static void detachToOverlay(WebView view) {
        if (view == null || destroyed || view != webView) {
            return;
        }
        removeFromOverlay();
        ViewGroup parent = (ViewGroup) view.getParent();
        if (parent != null) {
            parent.removeView(view);
        }
        attached = false;
        keepVisibleInOverlay();
    }

    /**
     * Backgrounded Android WebViews are marked HIDDEN by Chromium, which
     * intensively throttles the page's JS timers — the SPA's relay heartbeat
     * goes silent and the desktop drops the phone ("Desktop offline",
     * live-reproduced 2026-10-25). Pinning the idle view in an entirely
     * offscreen TYPE_APPLICATION_OVERLAY window keeps the page VISIBLE to
     * Chromium (heartbeat alive, worst case 1 s timer alignment) while being
     * unfocusable, untouchable and offscreen for the user. Needs the
     * already-provisioned overlay permission; degrades silently to the old
     * detach behavior without it.
     */
    public static void keepVisibleInOverlay() {
        if (webView == null || destroyed || attached || inOverlay) {
            return;
        }
        try {
            Context app = webView.getContext().getApplicationContext();
            WindowManager wm =
                    (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null || !Settings.canDrawOverlays(app)) {
                return;
            }
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                            | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                    PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.START;
            // Entirely offscreen: never over the user's work, never composited.
            p.x = -2 * app.getResources().getDisplayMetrics().widthPixels;
            p.y = 0;
            wm.addView(webView, p);
            webView.setVisibility(View.VISIBLE);
            inOverlay = true;
            MAIN.removeCallbacks(idleHealthScan);
            MAIN.postDelayed(idleHealthScan, 15000L);
            Log.i(TAG, "session WebView pinned offscreen-visible: heartbeat stays alive");
        } catch (Throwable t) {
            Log.w(TAG, "overlay pin failed; background page will throttle", t);
        }
    }

    private static void removeFromOverlay() {
        MAIN.removeCallbacks(idleHealthScan);
        desktopOfflineSince = 0L;
        if (!inOverlay) {
            return;
        }
        inOverlay = false;
        if (webView == null) {
            return;
        }
        try {
            Context app = webView.getContext().getApplicationContext();
            WindowManager wm =
                    (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                wm.removeViewImmediate(webView);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Service watchdog: re-pin when the system dropped the overlay window. */
    public static void ensureOverlayAlive() {
        if (webView != null && !destroyed && !attached && !inOverlay
                && webView.getParent() == null) {
            keepVisibleInOverlay();
        }
    }

    /** URL changed (rotation heal): reload the shared view if it is idle. */
    public static void reloadIfIdle(String url) {
        if (webView != null && !destroyed && !attached && url != null && !url.isEmpty()) {
            MAIN.post(() -> {
                loadedUrl = url;
                webView.loadUrl(url);
            });
        }
    }

    /** Waits for any network, then prewarms (used at boot). */
    public static void ensureLoadedWhenNetworkUp(Context appContext, String url) {
        Context app = appContext.getApplicationContext();
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                ensureLoaded(app, url);
                return;
            }
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    ensureLoaded(app, url);
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            ensureLoaded(app, url);
        }
    }
}
