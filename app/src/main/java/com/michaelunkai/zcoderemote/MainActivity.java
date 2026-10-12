package com.michaelunkai.zcoderemote;

import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;
    private static final Pattern HTTP_URL_PATTERN = Pattern.compile("https?://\\S+");

    private UrlStore urlStore;
    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private View errorOverlay;
    private TextView errorMessage;
    private ValueCallback<Uri[]> fileUploadCallback;
    private String lastLoadedUrl = null;
    private boolean mainFrameError;

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
                if (mainFrameError) {
                    mainFrameError = false;
                } else {
                    hideError();
                }
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
        hideError();
        webView.loadUrl(effective);
    }

    private void showError(String message) {
        errorMessage.setText(message);
        errorOverlay.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
        swipeRefresh.setRefreshing(false);
    }

    private void hideError() {
        errorOverlay.setVisibility(View.GONE);
    }
}
