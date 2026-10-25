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

    /**
     * Fit guard: the session site targets phones, but if a future page ships
     * without a device-width viewport (or a desktop one), the WebView would
     * lay out at ~1000 px and render a shrunken desktop shell. Force
     * device-width + scale 1 so every page always fits the screen exactly.
     * No-op when the page already declares the correct viewport.
     */
    private static final String VIEWPORT_FIT_JS =
            "(function(){"
            + "var c='width=device-width,initial-scale=1';"
            + "var m=document.querySelector('meta[name=viewport]');"
            + "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');"
            + "document.head.appendChild(m);m.setAttribute('content',c);return 'added';}"
            + "var cur=m.getAttribute('content')||'';"
            + "if(cur.replace(/\\s/g,'').indexOf('width=device-width')<0){"
            + "m.setAttribute('content',c);return 'forced';}"
            + "return 'ok';"
            + "})();";

    /**
     * The SPA pauses its relay heartbeat when it believes the page is hidden
     * (document.hidden). Android flips that on every backgrounding/lock;
     * spoof the getters so the page always reports visible — combined with
     * SessionWebViewHolder's offscreen overlay window this keeps the session
     * alive in background and with the screen off.
     */
    private static final String VISIBILITY_JS = SessionWebViewHolder.VISIBILITY_JS;

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
    private long lastForcedReloadAt = 0L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoRetry = this::autoRetryOnce;
    private final Runnable offlineReload = this::offlineReloadOnce;
    private int retryAttempt = 0;
    private int pageChecksRemaining = 0;
    private long sessionLoadStartedAt = 0L;
    private boolean stuckNoticeShown = false;
    private boolean orphanNoticeShown = false;
    private boolean deviceOfflineNoticeShown = false;
    private View reconnectVeil;
    private ViewGroup webViewHomeParent;
    private int webViewHomeIndex = -1;
    private ViewGroup.LayoutParams webViewHomeParams;

    /**
     * Continuous page health scan (while resumed): surfaces the rotation-
     * invalid page and the never-finishing relay wizard as actionable
     * overlays instead of leaving the user on a dead silent screen.
     */
    private final Runnable pageHealthCheck = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || localAgentMode || mainFrameError || webView == null) {
                return;
            }
            scanForInvalidLink(webView);
            if (pageChecksRemaining > 0) {
                pageChecksRemaining--;
                mainHandler.postDelayed(this, 5000L);
            }
        }
    };

    private void restartPageHealthChecks() {
        pageChecksRemaining = 36; // ~3 min of checks after each load/resume
        mainHandler.removeCallbacks(pageHealthCheck);
        mainHandler.postDelayed(pageHealthCheck, 5000L);
    }

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
        AccessibilityHealer.heal(this);
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

        adoptSharedSessionView();
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
        Button btnScanQr = findViewById(R.id.btn_scan_qr);
        btnScanQr.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, QrScanActivity.class)));

        // Calm reconnecting veil (owner directive: NEVER show the scary
        // "Desktop offline"/"session ended" pages): covers the WebView only
        // while the session is in a retry state, auto-clears on recovery.
        reconnectVeil = buildReconnectVeil();

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
     * (loopback model server) without ever touching the stored session URL;
     * `--ez reload_url true` (bridge /seturl) reloads the freshly pushed URL.
     */
    private void maybeHandleLaunchExtras(Intent intent) {
        if (intent != null && intent.getBooleanExtra("open_panel", false)) {
            startActivity(new Intent(this, DevicePanelActivity.class));
        }
        if (intent != null && intent.getBooleanExtra("open_local_agent", false)) {
            retryAttempt = 0;
            enterLocalAgent();
        }
        if (intent != null && intent.getBooleanExtra("reload_url", false)) {
            retryAttempt = 0;
            stuckNoticeShown = false;
            orphanNoticeShown = false;
            loadEffectiveUrl();
        }
    }

    /**
     * The keep-alive service holds an offscreen, already-connected session
     * WebView (boot prewarm, SessionWebViewHolder). When it matches the
     * stored URL, adopt it in place of the XML-declared view: opening the
     * app then lands on the live page in well under a second instead of
     * paying the ~9 s server-side relay handshake. The XML view is
     * destroyed; the shared one is re-wired with this activity's clients.
     */
    private void adoptSharedSessionView() {
        String target = urlStore.get();
        if (target.isEmpty() || !target.equals(SessionWebViewHolder.loadedUrl())) {
            SessionWebViewHolder.register(webView, target);
            return;
        }
        WebView shared = SessionWebViewHolder.adopt();
        if (shared == null || shared == webView) {
            SessionWebViewHolder.register(webView, target);
            return;
        }
        ViewGroup parent = (ViewGroup) webView.getParent();
        ViewGroup.LayoutParams params = webView.getLayoutParams();
        int index = parent.indexOfChild(webView);
        parent.removeView(webView);
        webView.destroy();
        webView = shared;
        parent.addView(webView, index, params);
        lastLoadedUrl = target;
        // The page already finished offscreen: re-bind the scroll watcher now
        // (onPageFinished will not re-fire for an already-loaded page).
        innerScrolledUp = false;
        webView.evaluateJavascript(SCROLL_WATCHER_JS, null);
        webView.evaluateJavascript(VIEWPORT_FIT_JS, null);
        webView.evaluateJavascript(VISIBILITY_JS, null);
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
                view.evaluateJavascript(VIEWPORT_FIT_JS, null);
                view.evaluateJavascript(VISIBILITY_JS, null);
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
                        restartPageHealthChecks();
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
    protected void onStart() {
        super.onStart();
        // Take the session view back from the offscreen overlay window (see
        // onStop) so it is live in our layout before onResume runs.
        if (webView != null && webViewHomeParent != null
                && webView.getParent() == null) {
            webViewHomeParent.addView(webView, webViewHomeIndex, webViewHomeParams);
        }
        webViewHomeParent = null;
    }

    @Override
    protected void onStop() {
        super.onStop();
        // A stopped activity hides its window, Chromium marks the page hidden
        // and the relay heartbeat throttles to death. Hop the session view
        // into the holder's offscreen overlay (page stays VISIBLE) and take
        // it back in onStart; the layout slot is remembered for the swap.
        if (isChangingConfigurations() || webView == null || localAgentMode) {
            return;
        }
        ViewGroup parent = (ViewGroup) webView.getParent();
        if (parent != null) {
            webViewHomeParent = parent;
            webViewHomeIndex = parent.indexOfChild(webView);
            webViewHomeParams = webView.getLayoutParams();
            SessionWebViewHolder.detachToOverlay(webView);
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
        } else if (!effective.equals(lastLoadedUrl) || webView.getUrl() == null) {
            loadEffectiveUrl();
        }
        if (!localAgentMode) {
            restartPageHealthChecks();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Foreground-only polling: zero battery cost while backgrounded.
        stopPcProbe();
        mainHandler.removeCallbacks(pageHealthCheck);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Hand the live page back to the keep-alive holder: it keeps running
        // offscreen in the service-held process, so the next open is an
        // instant adopt instead of a cold relay handshake.
        SessionWebViewHolder.handBack();
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
        stuckNoticeShown = false;
        orphanNoticeShown = false;
        sessionLoadStartedAt = SystemClock.elapsedRealtime();
        SessionHealth.loadStarted();
        mainHandler.removeCallbacks(autoRetry);
        hideError();
        webView.loadUrl(effective);
        SessionWebViewHolder.register(webView, effective);
    }

    /**
     * Enters offline-capable local mode: the WebView shows the on-device AI
     * agent (loopback model server). Works with the PC on or off; the probe
     * chip tells the user the moment the PC session is reachable again.
     */
    private void enterLocalAgent() {
        localAgentMode = true;
        lastErrorKind = ConnectionErrorKind.OTHER;
        SessionHealth.set(SessionHealth.LOCAL_AGENT);
        lastLoadedUrl = null;
        mainFrameError = false;
        mainHandler.removeCallbacks(autoRetry);
        hideError();
        webView.loadUrl(LOCAL_AGENT_URL);
        SessionWebViewHolder.register(webView, LOCAL_AGENT_URL);
        updateReconnectVeil(false);
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
            SessionWebViewHolder.register(webView, urlStore.get());
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
        SessionHealth.set(SessionHealth.ERROR);
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

    private void scheduleOfflineReload() {
        mainHandler.removeCallbacks(offlineReload);
        long delayMs = Math.min(20000L, 10000L + 2000L * Math.min(retryAttempt, 5));
        mainHandler.postDelayed(offlineReload, delayMs);
    }

    private void offlineReloadOnce() {
        if (!mainFrameError && !localAgentMode && !isFinishing()
                && !urlStore.get().isEmpty()) {
            retryCurrentTarget();
        }
    }

    /**
     * Scans the loaded session page for failure markers. Runs once at page
     * finish and then periodically while resumed: the rotation-invalid page
     * and the hung relay wizard both render LONG AFTER onPageFinished, so a
     * one-shot scan never saw them (the user stared at a dead page).
     */
    private void scanForInvalidLink(WebView view) {
        PageScan.scan(view, (state, detail) -> {
            if (state == null || state.isEmpty()
                    || mainFrameError || localAgentMode || isFinishing()) {
                return;
            }
            // Raw state first: the PC-side watchdog polls this through the
            // bridge /status even while the app sits closed in the pocket.
            SessionHealth.set(state);
            SessionHealth.setDetail(detail);
            updateReconnectVeil(!PageScan.OK.equals(state) && SessionHealth.ageSeconds() >= 5);
            if (PageScan.OK.equals(state)) {
                if (stuckNoticeShown || orphanNoticeShown || deviceOfflineNoticeShown) {
                    stuckNoticeShown = false;
                    orphanNoticeShown = false;
                    deviceOfflineNoticeShown = false;
                    mainHandler.removeCallbacks(offlineReload);
                    hideError();
                }
                return;
            }
            // NO OVERLAYS, EVER (owner directive 2026-10-25): every non-ok
            // state is transient. The SPA reconnects IN-PAGE (fast); a full
            // page reload restarts the whole slow handshake — so reload only
            // when genuinely wedged: the same state held >=75 s AND no forced
            // reload in the last 75 s. Otherwise stay out of the SPA's way.
            long nowMs = SystemClock.elapsedRealtime();
            if (nowMs - lastForcedReloadAt > 75000L && SessionHealth.ageSeconds() >= 75) {
                lastForcedReloadAt = nowMs;
                scheduleOfflineReload();
            }
        });
    }

    private View buildReconnectVeil() {
        android.widget.FrameLayout veil = new android.widget.FrameLayout(this);
        veil.setBackgroundColor(0xF20F0E17);
        veil.setClickable(true);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER);
        ProgressBar spinner = new ProgressBar(this);
        TextView label = new TextView(this);
        label.setText(R.string.veil_reconnecting);
        label.setTextColor(0xFFB9B4D0);
        label.setTextSize(15);
        label.setPadding(0, dp(14), 0, 0);
        label.setGravity(android.view.Gravity.CENTER);
        box.addView(spinner, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(label, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        veil.addView(box, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        veil.setVisibility(View.GONE);
        android.view.ViewGroup content = findViewById(android.R.id.content);
        content.addView(veil, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        return veil;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void updateReconnectVeil(boolean retrying) {
        if (reconnectVeil != null && !localAgentMode && !isFinishing()) {
            reconnectVeil.setVisibility(retrying ? View.VISIBLE : View.GONE);
        }
    }

    private void hideError() {
        errorOverlay.setVisibility(View.GONE);
    }
}
