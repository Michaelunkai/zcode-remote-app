package com.michaelunkai.zcoderemote;

import android.app.DownloadManager;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;
    private static final Pattern HTTP_URL_PATTERN = Pattern.compile("https?://\\S+");

    /**
     * Persistent per-page hook: reports whenever any scrollable container moves
     * away from its top (cheap O(1) per event; the full scan in
     * SCROLL_PROBE_JS re-syncs the exact state at the start of every gesture).
     */
    private static final String SCROLL_WATCHER_JS =
            "(function(){"
            + "function top(e){return !!e&&e.scrollTop>0;}"
            + "function scr(e){if(!e||e===document||e===document.documentElement||e===document.body)return false;"
            + "var s=window.getComputedStyle(e);"
            + "return s.overflowY==='auto'||s.overflowY==='scroll'||s.overflow==='auto'||s.overflow==='scroll';}"
            + "function docTop(){var d=document.scrollingElement||document.documentElement||document.body;return top(d);}"
            + "if(window.__zcHooked){AndroidScroll.onScrolled(docTop());return;}"
            + "window.__zcHooked=true;"
            + "window.addEventListener('scroll',function(ev){var t=ev.target;"
            + "var s=(t===document||t===document.documentElement||t===document.body)?docTop():(top(t)&&scr(t));"
            + "if(s){AndroidScroll.onScrolled(true);}},true);"
            + "AndroidScroll.onScrolled(docTop());"
            + "})();";

    /** Accurate full scan (document + every scrollable container) before a drag starts. */
    private static final String SCROLL_PROBE_JS =
            "(function(){"
            + "function top(e){return !!e&&e.scrollTop>0;}"
            + "function scr(e){if(!e||e===document||e===document.documentElement||e===document.body)return false;"
            + "var s=window.getComputedStyle(e);"
            + "return s.overflowY==='auto'||s.overflowY==='scroll'||s.overflow==='auto'||s.overflow==='scroll';}"
            + "function docTop(){var d=document.scrollingElement||document.documentElement||document.body;return top(d);}"
            + "if(docTop()){AndroidScroll.onScrolled(true);return;}"
            + "var all=document.querySelectorAll('*');"
            + "for(var i=0;i<all.length;i++){var e=all[i];"
            + "if(e.scrollTop>0&&scr(e)){AndroidScroll.onScrolled(true);return;}}"
            + "AndroidScroll.onScrolled(false);"
            + "})();";

    private UrlStore urlStore;
    private WebView webView;
    private SwipeRefreshGate swipeRefresh;
    private ProgressBar progressBar;
    private View errorOverlay;
    private TextView errorMessage;
    private ValueCallback<Uri[]> fileUploadCallback;
    private String lastLoadedUrl = null;
    private boolean mainFrameError;
    private volatile boolean innerScrolledUp = false;
    private long lastScrollProbeAt = 0L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoRetry = this::autoRetryOnce;
    private int retryAttempt = 0;

    /** JS bridge: the injected scroll watcher reports inner-container state. */
    private class ScrollWatcher {
        @JavascriptInterface
        public void onScrolled(boolean anyContainerScrolledUp) {
            innerScrolledUp = anyContainerScrolledUp;
        }
    }

    private final ScrollWatcher scrollWatcher = new ScrollWatcher();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Deliberate user requirement: the session stays visible and interactive
            // above the secure lockscreen (the keyguard itself is never dismissed).
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        urlStore = new UrlStore(this);
        webView = findViewById(R.id.web_view);
        swipeRefresh = findViewById(R.id.swipe_refresh);
        progressBar = findViewById(R.id.progress_bar);
        errorOverlay = findViewById(R.id.error_overlay);
        errorMessage = findViewById(R.id.error_message);
        Button btnRetry = findViewById(R.id.btn_retry);
        Button btnChangeUrl = findViewById(R.id.btn_change_url);

        configureWebViewSettings();
        configureWebViewClients();
        registerNetworkHeal();

        swipeRefresh.setGate(() -> innerScrolledUp);

        swipeRefresh.setOnRefreshListener(() -> {
            if (webView.getUrl() != null) {
                webView.reload();
            } else {
                swipeRefresh.setRefreshing(false);
                loadEffectiveUrl();
            }
        });

        btnRetry.setOnClickListener(v -> loadEffectiveUrl());
        btnChangeUrl.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, SettingsActivity.class)));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    moveTaskToBack(true);
                }
            }
        });
    }

    private void configureWebViewSettings() {
        WebSettings settings = webView.getSettings();
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
        // Live progress guarantee: the renderer keeps full priority even while the
        // app is backgrounded, so the page (sessions menu, running tasks) never
        // freezes or dies — returning lands on an already-updated page instantly.
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.addJavascriptInterface(scrollWatcher, "AndroidScroll");
        webView.addJavascriptInterface(new ZcodeBridge(this, () ->
                webView == null ? null : webView.getUrl()), "ZcodeAndroid");
    }

    private void configureWebViewClients() {
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                String scheme = url.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (Exception e) {
                    // No activity can handle this scheme; stay on the current page.
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                    WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    // onPageFinished fires after this even on failure, so flag the
                    // failure and let onPageFinished decide whether to hide.
                    mainFrameError = true;
                    CharSequence description = error.getDescription();
                    String message = getString(R.string.error_generic);
                    if (description != null && description.length() > 0) {
                        message = message + "\n" + description;
                    }
                    showError(message);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                innerScrolledUp = false;
                view.evaluateJavascript(SCROLL_WATCHER_JS, null);
                if (mainFrameError) {
                    mainFrameError = false;
                } else {
                    retryAttempt = 0;
                    mainHandler.removeCallbacks(autoRetry);
                    hideError();
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // The renderer was killed (OOM / system pressure). Rebuild it in
                // place and restore the session instead of taking the app down.
                rebuildWebView();
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress > 0 && newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
                if (newProgress >= 100) {
                    swipeRefresh.setRefreshing(false);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = filePathCallback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                } catch (Exception e) {
                    fileUploadCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                    GeolocationPermissions.Callback callback) {
                callback.invoke(origin, false, false);
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.deny();
            }
        });

        webView.setDownloadListener(this::handleDownload);
    }

    private void handleDownload(String url, String userAgent, String contentDisposition,
            String mimetype, long contentLength) {
        try {
            String fileName = Uri.parse(url).getLastPathSegment();
            if (fileName == null || fileName.trim().isEmpty()) {
                fileName = "download";
            }
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) {
                request.addRequestHeader("Cookie", cookie);
            }
            if (userAgent != null) {
                request.addRequestHeader("User-Agent", userAgent);
            }
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setMimeType(mimetype);
            request.setTitle(fileName);
            DownloadManager downloadManager =
                    (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (downloadManager != null) {
                downloadManager.enqueue(request);
                Toast.makeText(this, R.string.toast_download_started, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            // The system DownloadManager refused the request; nothing else to do.
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            maybeProbeScrollState();
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * Re-syncs the exact scroll state at the start of every gesture: the cheap
     * scroll listener can go stale (a container scrolled back to its top only
     * leaves a stale "scrolled" flag), and SwipeRefreshGate reads the flag
     * synchronously while the drag develops.
     */
    private void maybeProbeScrollState() {
        if (webView == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastScrollProbeAt < 200L) {
            return;
        }
        lastScrollProbeAt = now;
        webView.evaluateJavascript(SCROLL_PROBE_JS, null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) {
            return;
        }
        String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (sharedText == null) {
            return;
        }
        Matcher matcher = HTTP_URL_PATTERN.matcher(sharedText);
        if (matcher.find() && urlStore.save(matcher.group())) {
            loadEffectiveUrl();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Keep the session page alive (and the hotword listener armed) as long as
        // the user wants them: returning to the app must land on a live page in
        // well under a second, with progress already up to date.
        SessionKeepService.start(this);
        HotwordService.startIfEnabled(this);
        String effective = urlStore.get();
        if (effective.isEmpty()) {
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        if (!effective.equals(lastLoadedUrl)) {
            loadEffectiveUrl();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE && fileUploadCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[] { data.getData() };
            }
            fileUploadCallback.onReceiveValue(result);
            fileUploadCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void loadEffectiveUrl() {
        String effective = urlStore.get();
        if (effective.isEmpty()) {
            return;
        }
        lastLoadedUrl = effective;
        mainFrameError = false;
        mainHandler.removeCallbacks(autoRetry);
        hideError();
        webView.loadUrl(effective);
    }

    /** Rebuilds the WebView after its renderer died, restoring the live session. */
    private void rebuildWebView() {
        try {
            ViewGroup parent = (ViewGroup) webView.getParent();
            int index = parent.indexOfChild(webView);
            parent.removeView(webView);
            webView.destroy();
            webView = new WebView(this);
            webView.setId(R.id.web_view);
            configureWebViewSettings();
            configureWebViewClients();
            ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            parent.addView(webView, index, params);
            mainFrameError = false;
            loadEffectiveUrl();
        } catch (Exception e) {
            // Last resort: recreate the whole activity around a fresh WebView.
            recreate();
        }
    }

    /** When the network comes back, heal a dead main frame immediately. */
    private void registerNetworkHeal() {
        ConnectivityManager connectivity =
                (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (connectivity == null) {
            return;
        }
        try {
            connectivity.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    mainHandler.postDelayed(() -> {
                        if (mainFrameError && !isFinishing()) {
                            retryAttempt = 0;
                            loadEffectiveUrl();
                        }
                    }, 400L);
                }
            });
        } catch (Exception e) {
            // Callbacks unavailable; the auto-retry backoff still covers recovery.
        }
    }

    /** Capped backoff while the error overlay is showing: 1s, 2s, 4s, 8s, 15s, 15s… */
    private void scheduleAutoRetry() {
        mainHandler.removeCallbacks(autoRetry);
        long delayMs = Math.min(15000L, 1000L << Math.min(retryAttempt, 4));
        retryAttempt++;
        mainHandler.postDelayed(autoRetry, delayMs);
    }

    private void autoRetryOnce() {
        if (mainFrameError && !isFinishing()) {
            loadEffectiveUrl();
        }
    }

    private void showError(String message) {
        errorMessage.setText(message);
        errorOverlay.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
        swipeRefresh.setRefreshing(false);
        scheduleAutoRetry();
    }

    private void hideError() {
        errorOverlay.setVisibility(View.GONE);
    }
}
