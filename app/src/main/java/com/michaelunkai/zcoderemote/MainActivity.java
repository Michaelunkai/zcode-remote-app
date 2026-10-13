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

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;
    private static final Pattern HTTP_URL_PATTERN = Pattern.compile("https?://\\S+");

    /** The on-device model server (offline-mode AI agent). Loopback only. */
    private static final String LOCAL_AGENT_URL = "http://127.0.0.1:8080";

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
    private TextView offlineTitle;
    private TextView returnNotice;
    private Button btnBackSession;
    private ValueCallback<Uri[]> fileUploadCallback;
    private String lastLoadedUrl = null;
    private boolean mainFrameError;
    private boolean localAgentMode = false;
    private ConnectionErrorKind lastErrorKind = ConnectionErrorKind.OTHER;
    private boolean pcProbeActive = false;
    private ExecutorService pcProbeExecutor;
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
        offlineTitle = findViewById(R.id.offline_title);
        returnNotice = findViewById(R.id.return_notice);
        btnBackSession = findViewById(R.id.btn_back_session);
        Button btnRetry = findViewById(R.id.btn_retry);
        Button btnChangeUrl = findViewById(R.id.btn_change_url);
        Button btnDevicePanel = findViewById(R.id.btn_device_panel);
        Button btnLocalAgent = findViewById(R.id.btn_local_agent);

        configureWebViewSettings();
        configureWebViewClients();
        registerNetworkHeal();

        swipeRefresh.setGate(() -> innerScrolledUp);

        swipeRefresh.setOnRefreshListener(() -> {
            if (webView.getUrl() != null) {
                webView.reload();
            } else {
                swipeRefresh.setRefreshing(false);
                retryCurrentTarget();
            }
        });

        btnRetry.setOnClickListener(v -> retryCurrentTarget());
        btnChangeUrl.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, SettingsActivity.class)));
        btnDevicePanel.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, DevicePanelActivity.class)));
        btnLocalAgent.setOnClickListener(v -> {
            retryAttempt = 0;
            enterLocalAgent();
        });
        btnBackSession.setOnClickListener(v -> exitLocalAgent());
        returnNotice.setOnClickListener(v -> exitLocalAgent());

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

        maybeHandleLaunchExtras(getIntent());
    }

    /**
     * Deterministic adb entries: `--ez open_panel true` opens the device
     * panel; `--ez open_local_agent true` switches to the on-device AI view
     * (loopback model server) without ever touching the stored session URL.
     */
    private void maybeHandleLaunchExtras(Intent intent) {
        if (intent != null && intent.getBooleanExtra("open_panel", false)) {
            startActivity(new Intent(this, DevicePanelActivity.class));
        }
        if (intent != null && intent.getBooleanExtra("open_local_agent", false)) {
            retryAttempt = 0;
            enterLocalAgent();
        }
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
                    lastErrorKind = ConnectionErrorKind.classify(
                            description == null ? null : description.toString());
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
                    if (!localAgentMode) {
                        // A rotation-invalidated link renders as a loaded page
                        // ("Mobile Connection Invalid"/AUTH_FAILED), not an error:
                        // surface the update-URL guidance instead of a blank retry.
                        scanForInvalidLink(view);
                    }
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
        maybeHandleLaunchExtras(intent);
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
        // Keep the session page alive as long as the user wants it: returning to
        // the app must land on a live page in well under a second, with progress
        // already up to date. Local-agent mode is left untouched so returning
        // from the panel never kicks the user out of the offline view.
        SessionKeepService.start(this);
        String effective = urlStore.get();
        if (effective.isEmpty()) {
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        if (localAgentMode) {
            startPcProbe();
        } else if (!effective.equals(lastLoadedUrl)) {
            loadEffectiveUrl();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Foreground-only polling: zero battery cost while backgrounded.
        stopPcProbe();
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
        localAgentMode = false;
        lastErrorKind = ConnectionErrorKind.OTHER;
        returnNotice.setVisibility(View.GONE);
        lastLoadedUrl = effective;
        mainFrameError = false;
        mainHandler.removeCallbacks(autoRetry);
        hideError();
        webView.loadUrl(effective);
    }

    /**
     * Enters offline-capable local mode: the WebView shows the on-device AI
     * agent (loopback model server). Works with the PC on or off; the probe
     * chip tells the user the moment the PC session is reachable again.
     */
    private void enterLocalAgent() {
        localAgentMode = true;
        lastErrorKind = ConnectionErrorKind.OTHER;
        lastLoadedUrl = null;
        mainFrameError = false;
        mainHandler.removeCallbacks(autoRetry);
        hideError();
        webView.loadUrl(LOCAL_AGENT_URL);
        // Fresh back list: Back from the agent minimizes instead of landing on
        // the dead session page; exitLocalAgent reloads the session explicitly.
        webView.clearHistory();
        startPcProbe();
    }

    /** Returns to the PC session (Back button or the PC-is-back notice). */
    private void exitLocalAgent() {
        stopPcProbe();
        loadEffectiveUrl();
    }

    /** Retries whichever target the user is on: the local agent or the PC. */
    private void retryCurrentTarget() {
        if (localAgentMode) {
            enterLocalAgent();
        } else {
            loadEffectiveUrl();
        }
    }

    /** While in local mode, watch for the PC coming back (20 s, foreground only). */
    private void startPcProbe() {
        if (!localAgentMode || pcProbeActive || urlStore.get().isEmpty()) {
            return;
        }
        pcProbeActive = true;
        mainHandler.post(pcProbeLoop);
    }

    private void stopPcProbe() {
        pcProbeActive = false;
        mainHandler.removeCallbacks(pcProbeLoop);
        returnNotice.setVisibility(View.GONE);
    }

    private final Runnable pcProbeLoop = new Runnable() {
        @Override
        public void run() {
            if (!pcProbeActive || !localAgentMode || isFinishing()) {
                return;
            }
            final String target = urlStore.get();
            if (pcProbeExecutor == null) {
                pcProbeExecutor = Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "pc-probe");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            pcProbeExecutor.execute(() -> {
                final boolean reachable = isHostReachable(target);
                mainHandler.post(() -> {
                    if (pcProbeActive && localAgentMode && !isFinishing()) {
                        returnNotice.setVisibility(reachable ? View.VISIBLE : View.GONE);
                        if (pcProbeActive && localAgentMode && !isFinishing()) {
                            mainHandler.postDelayed(pcProbeLoop, 20000L);
                        }
                    }
                });
            });
        }
    };

    /** HEAD request with a tight timeout; true on any real HTTP response. */
    private static boolean isHostReachable(String target) {
        if (target == null || target.isEmpty()) {
            return false;
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(target).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            connection.setInstanceFollowRedirects(true);
            return connection.getResponseCode() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
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
                            retryCurrentTarget();
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
            retryCurrentTarget();
        }
    }

    private void showError(String message) {
        boolean offline = lastErrorKind == ConnectionErrorKind.UNREACHABLE;
        offlineTitle.setText(localAgentMode
                ? R.string.local_agent_down_title
                : (offline ? R.string.offline_title : R.string.error_title));
        offlineTitle.setVisibility(
                offline || localAgentMode ? View.VISIBLE : View.GONE);
        errorMessage.setText(localAgentMode
                ? getString(R.string.local_agent_down_hint) + "\n\n" + message
                : (offline ? getString(R.string.offline_hint) + "\n\n" + message : message));
        btnBackSession.setVisibility(localAgentMode ? View.VISIBLE : View.GONE);
        errorOverlay.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
        swipeRefresh.setRefreshing(false);
        scheduleAutoRetry();
    }

    /**
     * The PC rotated the remote link: the page loads fine but the session is
     * dead ("Mobile Connection Invalid"/AUTH_FAILED). Retrying cannot fix it —
     * stop the retry loop and point the user at Change URL instead.
     */
    private void showInvalidLink() {
        mainHandler.removeCallbacks(autoRetry);
        offlineTitle.setVisibility(View.VISIBLE);
        offlineTitle.setText(R.string.invalid_link_title);
        errorMessage.setText(getString(R.string.invalid_link_hint));
        btnBackSession.setVisibility(View.GONE);
        errorOverlay.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
        swipeRefresh.setRefreshing(false);
    }

    /** One-shot scan of the loaded session page for rotation-invalid markers. */
    private void scanForInvalidLink(WebView view) {
        view.evaluateJavascript(
                "(function(){var t=(document.body&&document.body.innerText)||'';"
                + "return (t.indexOf('Connection Invalid')>=0||t.indexOf('AUTH_FAILED')>=0)"
                + "?'true':'false';})()",
                value -> {
                    if (value != null && value.contains("true") && !mainFrameError
                            && !localAgentMode && !isFinishing()) {
                        showInvalidLink();
                    }
                });
    }

    private void hideError() {
        errorOverlay.setVisibility(View.GONE);
    }
}
