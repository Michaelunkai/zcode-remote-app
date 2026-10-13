package com.michaelunkai.zcoderemote;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * The no-root device-control engine. Once the user enables this service, the
 * app can drive the whole Android environment — global navigation, injected
 * gestures, text input, screenshots — exactly like desktop-side remote control,
 * but local: it keeps working even when the PC is offline.
 */
public class ZcodeControlAccessibilityService extends AccessibilityService {

    private static final String TAG = "ZcodeControl";
    private static volatile ZcodeControlAccessibilityService instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static boolean isReady() {
        return instance != null;
    }

    public static ZcodeControlAccessibilityService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
        // Tracks the foreground app so agent/panel queries know what is on screen.
        if (event != null
                && event.getEventType() == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && event.getPackageName() != null) {
            lastForegroundPackage = event.getPackageName().toString();
            lastForegroundAt = System.currentTimeMillis();
        }
    }

    private volatile String lastForegroundPackage;
    private volatile long lastForegroundAt;

    public static String getForegroundPackage() {
        ZcodeControlAccessibilityService service = instance;
        if (service == null) {
            return null;
        }
        // Stale by more than 10 s means no fresh window events (rare); still report.
        return service.lastForegroundPackage;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (instance == this) {
            instance = null;
        }
        return super.onUnbind(intent);
    }

    public void performGlobal(int action, DeviceActions.Callback callback) {
        boolean ok = false;
        try {
            ok = performGlobalAction(action);
        } catch (Exception e) {
            Log.w(TAG, "global action " + action + " failed", e);
        }
        callback.onResult(ok ? context().getString(R.string.action_ok)
                : context().getString(R.string.action_failed));
    }

    public void tap(int x, int y, DeviceActions.Callback callback) {
        Path path = new Path();
        path.moveTo(x, y);
        dispatch(path, 0, 60, callback);
    }

    public void longPress(int x, int y, DeviceActions.Callback callback) {
        Path path = new Path();
        path.moveTo(x, y);
        dispatch(path, 0, 600, callback);
    }

    public void swipe(int x1, int y1, int x2, int y2, int durationMs, DeviceActions.Callback callback) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        dispatch(path, 0, Math.max(50, durationMs), callback);
    }

    private void dispatch(Path path, long startTime, long duration, DeviceActions.Callback callback) {
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, startTime, duration));
        boolean ok = dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                callback.onResult(context().getString(R.string.action_ok));
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                callback.onResult(context().getString(R.string.action_failed));
            }
        }, null);
        if (!ok) {
            callback.onResult(context().getString(R.string.action_failed));
        }
    }

    /** Sets text on the currently focused editable node (no root, no IME). */
    public void typeText(String text, DeviceActions.Callback callback) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            callback.onResult(context().getString(R.string.action_no_editable));
            return;
        }
        AccessibilityNodeInfo target = findFocusedEditable(root);
        if (target == null) {
            callback.onResult(context().getString(R.string.action_no_editable));
            return;
        }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        callback.onResult(ok ? context().getString(R.string.action_ok)
                : context().getString(R.string.action_failed));
    }

    private AccessibilityNodeInfo findFocusedEditable(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused != null && focused.isEditable()) {
            return focused;
        }
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 300) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) {
                continue;
            }
            if (node.isEditable() && node.isFocused()) {
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
        return null;
    }

    /** Captures the current display (API 30+) and saves it to Pictures/Screenshots. */
    public void takeScreenshot(DeviceActions.Callback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback.onResult(context().getString(R.string.action_screenshot_unsupported));
            return;
        }
        Executor executor = mainHandler::post;
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, executor, new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult screenshot) {
                try {
                    Bitmap hardware = Bitmap.wrapHardwareBuffer(
                            screenshot.getHardwareBuffer(), screenshot.getColorSpace());
                    if (hardware == null) {
                        callback.onResult(context().getString(R.string.action_failed));
                        return;
                    }
                    Bitmap bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false);
                    hardware.getHardwareBuffer().close();
                    String saved = saveBitmap(bitmap);
                    bitmap.recycle();
                    callback.onResult(saved);
                } catch (Exception e) {
                    Log.w(TAG, "screenshot save failed", e);
                    callback.onResult(context().getString(R.string.action_failed));
                }
            }

            @Override
            public void onFailure(int errorCode) {
                callback.onResult(context().getString(R.string.action_failed));
            }
        });
    }

    private String saveBitmap(Bitmap bitmap) throws Exception {
        Context context = context();
        String name = "zcode_" + System.currentTimeMillis() + ".png";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Screenshots");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = context.getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IllegalStateException("MediaStore insert returned null");
            }
            try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            context.getContentResolver().update(uri, done, null, null);
            return context.getString(R.string.action_screenshot_saved, "Pictures/Screenshots/" + name);
        }
        java.io.File dir = new java.io.File(
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Screenshots");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create screenshot dir");
        }
        java.io.File file = new java.io.File(dir, name);
        try (OutputStream out = new java.io.FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        return context.getString(R.string.action_screenshot_saved, file.getAbsolutePath());
    }

    /** Reports the focused node's screen bounds (useful for the JS bridge targeting). */
    public void focusedBounds(BoundsCallback callback) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            callback.onBounds(null);
            return;
        }
        AccessibilityNodeInfo focused = findFocusedEditable(root);
        if (focused == null) {
            callback.onBounds(null);
            return;
        }
        Rect rect = new Rect();
        focused.getBoundsInScreen(rect);
        callback.onBounds(rect);
    }

    /**
     * Plays a song: polls the app's rendered search results and clicks the best
     * playable row, then confirms playback via the player controls. Rules from
     * a real failure: never click a node whose text equals the whole query
     * (that is the search field — clicking it only raises the keyboard);
     * prefer content-desc matches and rows below the app bar; skip the IME
     * window; poll early and click as soon as a good row renders; after a
     * click confirm a pause control appears, else try the next row.
     */
    /**
     * Fast path: the app's deep link already rendered the search results
     * (in-app, via its own deep-link activity). Poll briefly for the results'
     * Play control, click it, then confirm playback. If the deep link did not
     * open inside the target app by the deadline, invokes fallback so the
     * caller can run the launch+search pipeline instead.
     */
    public void deepLinkPlay(String expectedPackage, String query,
            Runnable fallback, DeviceActions.Callback callback) {
        String lowered = query.toLowerCase(Locale.ROOT).trim();
        java.util.List<String> words = new java.util.ArrayList<>();
        for (String word : lowered.split("[\\s,.:;\u2014\u2013()-]+")) {
            if (word.length() >= 4) {
                words.add(word);
            }
        }
        deepLinkScan(expectedPackage, lowered, words, false, 0, System.currentTimeMillis(),
                fallback, callback);
    }

    private static final long DEEPLINK_OPEN_DEADLINE_MS = 5000L;
    private static final long DEEPLINK_PLAY_WAIT_MS = 6000L;
    private static final long DEEPLINK_CONFIRM_MS = 6000L;

    private void deepLinkScan(String expectedPackage, String fullQuery,
            java.util.List<String> words, boolean playClicked, int confirmPolls,
            long startedAt, Runnable fallback, DeviceActions.Callback callback) {
        if (!isReady()) {
            callback.onResult(context().getString(R.string.action_failed));
            return;
        }
        long now = System.currentTimeMillis();
        // Playback must be REAL: a pause control or seek bar visible on screen.
        if (findNowPlayingControl() != null) {
            callback.onResult(context().getString(R.string.action_playing));
            return;
        }
        // Results page layouts without a Play pill (YouTube app style: rows play
        // on tap) are handled by the row scanner — with scrolling — after a
        // short window for a pill to appear (music apps show one).
        if (playClicked == false && now - startedAt > 2500L) {
            AccessibilityNodeInfo root = appWindowRoot();
            String rootPkg = root == null || root.getPackageName() == null
                    ? "" : root.getPackageName().toString();
            if (rootPkg.equals(expectedPackage)) {
                playSongScan(fullQuery, words, new java.util.HashSet<>(),
                        System.currentTimeMillis(), callback);
                return;
            }
        }
        AccessibilityNodeInfo root = appWindowRoot();
        String rootPkg = root == null || root.getPackageName() == null
                ? "" : root.getPackageName().toString();
        boolean opened = rootPkg.equals(expectedPackage);
        if (opened && !playClicked) {
            // Click the TOPMOST Play control — the results card's pill — but
            // only once its position is STABLE across two consecutive scans
            // (clicking mid-layout lands nowhere).
            AccessibilityNodeInfo play = topmostNodeMatching(root, 0, node -> {
                CharSequence desc = node.getContentDescription();
                CharSequence text = node.getText();
                android.graphics.Rect rect = new android.graphics.Rect();
                node.getBoundsInScreen(rect);
                return rect.top < 1600 && rect.width() >= 40
                        && ((desc != null && desc.toString().equalsIgnoreCase("play"))
                            || (text != null && text.toString().equalsIgnoreCase("play")));
            });
            if (play != null) {
                String key = rowKey(play);
                if (key.equals(triedPlayKey)) {
                    triedPlayKey = "";
                    clickRow(play);
                    mainHandler.postDelayed(() -> deepLinkScan(expectedPackage, fullQuery,
                            words, true, 0, startedAt, fallback, callback), 1100L);
                    return;
                }
                triedPlayKey = key;
            }
            mainHandler.postDelayed(() -> deepLinkScan(expectedPackage, fullQuery,
                    words, playClicked, confirmPolls, startedAt, fallback, callback), 350L);
            return;
        }
        if (playClicked) {
            AccessibilityNodeInfo pauseNode = appWindowRoot() == null ? null
                    : findNodeMatching(appWindowRoot(), 0, node -> {
                        android.graphics.Rect rect = new android.graphics.Rect();
                        node.getBoundsInScreen(rect);
                        if (!node.isVisibleToUser() || rect.isEmpty() || rect.top > 1600) {
                            return false;
                        }
                        CharSequence desc = node.getContentDescription();
                        CharSequence text = node.getText();
                        return (desc != null && desc.toString().toLowerCase(Locale.ROOT)
                                .contains("pause"))
                                || (text != null && text.toString().toLowerCase(Locale.ROOT)
                                .contains("pause"));
                    });
            if (pauseNode != null) {
                callback.onResult(context().getString(R.string.action_playing));
                return;
            }
            if (now - startedAt > DEEPLINK_OPEN_DEADLINE_MS + DEEPLINK_PLAY_WAIT_MS
                    + DEEPLINK_CONFIRM_MS) {
                callback.onResult(context().getString(R.string.action_failed));
                return;
            }
            mainHandler.postDelayed(() -> deepLinkScan(expectedPackage, fullQuery,
                    words, playClicked, confirmPolls + 1, startedAt, fallback, callback),
                    400L);
            return;
        }
        if (!opened && now - startedAt > DEEPLINK_OPEN_DEADLINE_MS) {
            fallback.run();
            return;
        }
        mainHandler.postDelayed(() -> deepLinkScan(expectedPackage, fullQuery,
                words, playClicked, confirmPolls, startedAt, fallback, callback), 350L);
    }

    private String triedPlayKey = "";

    /** Depth-first, but returns the node with the SMALLEST screen-top y. */
    private AccessibilityNodeInfo topmostNodeMatching(AccessibilityNodeInfo node, int depth,
            NodePredicate predicate) {
        if (node == null || depth > 26) {
            return null;
        }
        AccessibilityNodeInfo best = null;
        int bestTop = Integer.MAX_VALUE;
        if (predicate.matches(node)) {
            android.graphics.Rect rect = new android.graphics.Rect();
            node.getBoundsInScreen(rect);
            best = node;
            bestTop = rect.top;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = topmostNodeMatching(node.getChild(i), depth + 1,
                    predicate);
            if (found != null) {
                android.graphics.Rect rect = new android.graphics.Rect();
                found.getBoundsInScreen(rect);
                if (rect.top < bestTop) {
                    best = found;
                    bestTop = rect.top;
                }
            }
        }
        return best;
    }

    /**
     * Universal "play <query> in <app>" pipeline: the app is already launched;
     * click its Search control, inject the query into the focused field, then
     * hand off to playSongScan. Works on any app with a search box (no deep
     * links, so nothing ever opens in a browser).
     */
    public void searchAndPlay(String query, DeviceActions.Callback callback) {
        String lowered = query.toLowerCase(Locale.ROOT).trim();
        java.util.List<String> words = new java.util.ArrayList<>();
        for (String word : lowered.split("[\\s,.:;\u2014\u2013()-]+")) {
            if (word.length() >= 4) {
                words.add(word);
            }
        }
        searchPhase(lowered, words, false, false, System.currentTimeMillis(), callback);
    }

    private static final long SEARCH_DEADLINE_MS = 18000L;

    private void searchPhase(String fullQuery, java.util.List<String> words,
            boolean searchClicked, boolean typed, long startedAt,
            DeviceActions.Callback callback) {
        if (!isReady()) {
            callback.onResult(context().getString(R.string.action_failed));
            return;
        }
        long now = System.currentTimeMillis();
        if (now - startedAt > SEARCH_DEADLINE_MS) {
            callback.onResult(context().getString(R.string.action_no_match, fullQuery));
            return;
        }
        // Once the query is in, the generic scan takes over (rows + playback
        // confirmation + retries). It also detects an already-playing state.
        if (searchClicked && typed) {
            playSongScan(fullQuery, words, new java.util.HashSet<>(), startedAt, callback);
            return;
        }
        AccessibilityNodeInfo root = appWindowRoot();
        if (root == null) {
            mainHandler.postDelayed(() -> searchPhase(fullQuery, words, searchClicked,
                    typed, startedAt, callback), 400L);
            return;
        }
        if (!searchClicked) {
            AccessibilityNodeInfo searchControl = findNodeMatching(root, 0, node -> {
                if (!node.isClickable() && !node.isVisibleToUser()) {
                    return false;
                }
                CharSequence desc = node.getContentDescription();
                CharSequence text = node.getText();
                return (desc != null && desc.toString().toLowerCase(Locale.ROOT).contains("search"))
                        || (text != null && text.toString().toLowerCase(Locale.ROOT).contains("search"));
            });
            if (searchControl != null) {
                searchControl.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                mainHandler.postDelayed(() -> searchPhase(fullQuery, words, true,
                        typed, startedAt, callback), 700L);
                return;
            }
            mainHandler.postDelayed(() -> searchPhase(fullQuery, words, searchClicked,
                    typed, startedAt, callback), 400L);
            return;
        }
        // Search screen: inject the query into an editable field. The field
        // often carries stale text from a previous search — SET_TEXT replaces
        // the whole content, and we verify it actually landed.
        AccessibilityNodeInfo field = findEditableField(root);
        if (field != null) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    fullQuery);
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            final boolean[] landed = {false};
            CharSequence check = field.getText();
            if (check != null && check.toString().toLowerCase(Locale.ROOT)
                    .contains(fullQuery.toLowerCase(Locale.ROOT))) {
                landed[0] = true;
            }
            mainHandler.postDelayed(() -> searchPhase(fullQuery, words, true,
                    typed || landed[0], startedAt, callback), 900L);
            return;
        }
        mainHandler.postDelayed(() -> searchPhase(fullQuery, words, searchClicked,
                typed, startedAt, callback), 400L);
    }

    public void playSong(String query, DeviceActions.Callback callback) {
        String lowered = query.toLowerCase(Locale.ROOT).trim();
        java.util.List<String> words = new java.util.ArrayList<>();
        for (String word : lowered.split("[\\s,.:;\u2014\u2013()-]+")) {
            if (word.length() >= 4) {
                words.add(word);
            }
        }
        playSongScan(lowered, words, new java.util.HashSet<>(), System.currentTimeMillis(),
                callback);
    }

    private static final long PLAY_DEADLINE_MS = 15000L;
    private static final long PLAY_SCAN_INTERVAL_MS = 350L;

    private void playSongScan(String fullQuery, java.util.List<String> words,
            java.util.HashSet<String> triedRows, long startedAt,
            DeviceActions.Callback callback) {
        if (!isReady()) {
            callback.onResult(context().getString(R.string.action_failed));
            return;
        }
        long now = System.currentTimeMillis();
        if (findNowPlayingControl() != null) {
            callback.onResult(context().getString(R.string.action_playing));
            return;
        }
        if (now - startedAt > PLAY_DEADLINE_MS) {
            callback.onResult(context().getString(R.string.action_no_match, fullQuery));
            return;
        }
        AccessibilityNodeInfo target = findBestResultRow(words, fullQuery, triedRows);
        if (target != null) {
            triedRows.add(rowKey(target));
            clickRow(target);
            mainHandler.postDelayed(() -> playSongScan(fullQuery, words, triedRows,
                    startedAt, callback), 1400L);
            return;
        }
        if (now - startedAt > 2500L && triedRows.isEmpty()) {
            scrollDown();
        }
        mainHandler.postDelayed(() -> playSongScan(fullQuery, words, triedRows,
                startedAt, callback), PLAY_SCAN_INTERVAL_MS);
    }

    /**
     * Playback proof: a pause control, a seek bar, or the player screen's
     * tabs (UP NEXT / LYRICS / RELATED). Any of these means the click landed
     * and the player is open with audio running.
     */
    private AccessibilityNodeInfo findNowPlayingControl() {
        AccessibilityNodeInfo root = appWindowRoot();
        if (root == null) {
            return null;
        }
        return findNodeMatching(root, 0, node -> {
            android.graphics.Rect rect = new android.graphics.Rect();
            node.getBoundsInScreen(rect);
            if (!node.isVisibleToUser() || rect.isEmpty() || rect.height() <= 0
                    || rect.centerY() > 1850) {
                return false;
            }
            CharSequence desc = node.getContentDescription();
            CharSequence text = node.getText();
            String className = String.valueOf(node.getClassName());
            if (desc != null && desc.toString().toLowerCase(Locale.ROOT).contains("pause")) {
                return true;
            }
            if (className.contains("SeekBar")) {
                return true;
            }
            return text != null && (text.toString().equalsIgnoreCase("up next")
                    || text.toString().equalsIgnoreCase("lyrics"));
        });
    }

    /** Best untried playable row: content-desc matches outrank text matches. */
    private AccessibilityNodeInfo findBestResultRow(java.util.List<String> words,
            String fullQuery, java.util.HashSet<String> triedRows) {
        AccessibilityNodeInfo root = appWindowRoot();
        if (root == null) {
            return null;
        }
        java.util.List<AccessibilityNodeInfo> ranked = new java.util.ArrayList<>();
        collectResultRows(root, words, fullQuery, triedRows, ranked, 0);
        if (ranked.isEmpty()) {
            return null;
        }
        java.util.Map<AccessibilityNodeInfo, Integer> scores = new java.util.HashMap<>();
        java.util.Map<AccessibilityNodeInfo, Integer> tops = new java.util.HashMap<>();
        for (AccessibilityNodeInfo node : ranked) {
            scores.put(node, rowScore(node, words, fullQuery));
            android.graphics.Rect rect = new android.graphics.Rect();
            node.getBoundsInScreen(rect);
            tops.put(node, rect.top);
        }
        ranked.sort((a, b) -> {
            int byScore = Integer.compare(scores.get(b), scores.get(a));
            return byScore != 0 ? byScore : Integer.compare(tops.get(a), tops.get(b));
        });
        return ranked.get(0);
    }

    private void collectResultRows(AccessibilityNodeInfo node, java.util.List<String> words,
            String fullQuery, java.util.HashSet<String> triedRows,
            java.util.List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 26 || out.size() > 24) {
            return;
        }
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String combined = ((text == null ? "" : text.toString()) + " "
                + (desc == null ? "" : desc.toString())).toLowerCase(Locale.ROOT).trim();
        if (!combined.isEmpty() && !combined.equals(fullQuery)
                && !triedRows.contains(rowKey(node))) {
            // Ads and store pages hijack taps: never touch install/sponsored
            // rows, and require at least two query words so single-word
            // coincidence matches (ads, unrelated videos) don't win.
            String loweredCombined = combined.toLowerCase(Locale.ROOT);
            for (String poison : new String[]{"install", "sponsored", "app store",
                    "play store", "download now"}) {
                if (loweredCombined.contains(poison)) {
                    return;
                }
            }
            int matched = 0;
            String longest = "";
            for (String word : words) {
                if (word.length() > longest.length()) {
                    longest = word;
                }
            }
            for (String word : words) {
                if (combined.contains(word)) {
                    matched++;
                }
            }
            if (matched >= 2 || (matched >= 1 && combined.contains(longest))) {
                out.add(node);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectResultRows(node.getChild(i), words, fullQuery, triedRows, out, depth + 1);
        }
    }

    private int rowScore(AccessibilityNodeInfo node, java.util.List<String> words,
            String fullQuery) {
        int score = 0;
        CharSequence desc = node.getContentDescription();
        if (desc != null && !desc.toString().trim().isEmpty()) {
            score += 3; // player rows describe their action
        }
        String combined = ((node.getText() == null ? "" : node.getText().toString()) + " "
                + (desc == null ? "" : desc.toString())).toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (combined.contains(word)) {
                score += 2;
            }
        }
        if (combined.contains(fullQuery)) {
            score += 2;
        }
        // Prefer the clean original over remixes/mashups/covers: words in the
        // row that the user never asked for lower the rank.
        for (String token : combined.split("[\\s,:;|\\u2014\\u2013()\\[\\]]+")) {
            if (token.length() >= 4 && !words.contains(token) && !fullQuery.contains(token)) {
                score -= 1;
            }
        }
        android.graphics.Rect rect = new android.graphics.Rect();
        node.getBoundsInScreen(rect);
        if (rect.top > 260) {
            score += 2; // below the app bar / search field
        }
        return score;
    }

    /** Tap the node's live bounds center — touch beats ACTION_CLICK on the
     * music apps (their click handling ignores synthesized clicks). */
    private void clickRow(AccessibilityNodeInfo node) {
        android.graphics.Rect rect = new android.graphics.Rect();
        node.getBoundsInScreen(rect);
        if (rect.isEmpty()) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            return;
        }
        tapCenter(rect.centerX(), rect.centerY());
    }

    private void tapCenter(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 40));
        dispatchGesture(builder.build(), null, null);
    }

    private String rowKey(AccessibilityNodeInfo node) {
        android.graphics.Rect rect = new android.graphics.Rect();
        node.getBoundsInScreen(rect);
        CharSequence text = node.getText();
        return rect.flattenToString() + "|" + (text == null ? "" : text.toString());
    }

    private void scrollDown() {
        Path path = new Path();
        path.moveTo(540, 1500);
        path.lineTo(540, 700);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 250));
        dispatchGesture(builder.build(), null, null);
    }

    private AccessibilityNodeInfo findEditableField(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused != null && focused.isEditable()) {
            return focused;
        }
        return findNodeMatching(root, 0, node -> node.isEditable() && node.isVisibleToUser());
    }

    /** The application window (never the keyboard). */
    private AccessibilityNodeInfo appWindowRoot() {
        try {
            for (AccessibilityWindowInfo window : getWindows()) {
                if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root != null) {
                        return root;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return getRootInActiveWindow();
    }

    private interface NodePredicate {
        boolean matches(AccessibilityNodeInfo node);
    }

    private AccessibilityNodeInfo findNodeMatching(AccessibilityNodeInfo node, int depth,
            NodePredicate predicate) {
        if (node == null || depth > 26) {
            return null;
        }
        if (predicate.matches(node)) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findNodeMatching(node.getChild(i), depth + 1, predicate);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Captures the display as raw PNG bytes for the agent bridge. */
    public void capturePng(java.util.function.Consumer<byte[]> callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback.accept(null);
            return;
        }
        Executor executor = mainHandler::post;
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, executor,
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult screenshot) {
                        try {
                            Bitmap hardware = Bitmap.wrapHardwareBuffer(
                                    screenshot.getHardwareBuffer(), screenshot.getColorSpace());
                            if (hardware == null) {
                                callback.accept(null);
                                return;
                            }
                            Bitmap bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false);
                            hardware.getHardwareBuffer().close();
                            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                            bitmap.compress(Bitmap.CompressFormat.PNG, 60, out);
                            bitmap.recycle();
                            callback.accept(out.toByteArray());
                        } catch (Exception e) {
                            Log.w(TAG, "capture failed", e);
                            callback.accept(null);
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        callback.accept(null);
                    }
                });
    }

    /** Wakes the screen so remote automation works even from a dark pocket. */
    public static void wakeScreenIfOff(Context context) {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                @SuppressWarnings("deprecation")
                android.os.PowerManager.WakeLock wake = pm.newWakeLock(
                        android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "zcode:autoWake");
                wake.acquire(6000L);
            }
        } catch (Exception ignored) {
            // Best effort; the action still runs when the user next wakes the screen.
        }
    }

    public Handler mainHandler() {
        return mainHandler;
    }

    private Context context() {
        return this;
    }

    public interface BoundsCallback {
        void onBounds(Rect rect);
    }
}
