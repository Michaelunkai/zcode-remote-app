package com.michaelunkai.zcoderemote;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
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
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

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
            return; // the live activity owns the view and manages its own URLs
        }
        if (webView != null && !destroyed && url.equals(loadedUrl)) {
            return; // offscreen view already holds this page; nothing to do
        }
        MAIN.post(() -> createAndLoad(appContext.getApplicationContext(), url));
    }

    private static void createAndLoad(Context app, String url) {
        if (attached) {
            return;
        }
        if (webView != null && !destroyed) {
            if (url.equals(loadedUrl)) {
                return;
            }
            // Different URL (rotation healed from the PC): reload in place —
            // keeps cookies/tabs state and avoids a second WebView instance.
            loadedUrl = url;
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
                public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                    destroyed = true;
                    webView = null;
                    loadedUrl = "";
                    Log.w(TAG, "prewarmed renderer died; will re-prewarm on next ensureLoaded");
                    MAIN.postDelayed(() -> ensureLoaded(app, url), 3000L);
                    return true;
                }

                @Override
                public void onPageFinished(WebView v, String pageUrl) {
                    Log.i(TAG, "prewarmed page finished: " + (pageUrl == null ? "?" : "ok"));
                }
            });
            webView = view;
            destroyed = false;
            attached = false;
            loadedUrl = url;
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
        ViewGroup parent = (ViewGroup) webView.getParent();
        if (parent != null) {
            parent.removeView(webView);
        }
        attached = false;
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
