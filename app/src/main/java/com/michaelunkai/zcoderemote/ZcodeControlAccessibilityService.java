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
     * Clicks the first on-screen node matching the query. Tolerates result-page
     * wording: tries the full phrase, then its significant words (longest
     * first), re-scanning while the page renders (results load late).
     */
    public void clickNodeContaining(String query, DeviceActions.Callback callback) {
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        String lowered = query.toLowerCase(Locale.ROOT).trim();
        candidates.add(lowered);
        String[] words = lowered.split("[\s,.:;—–()-]+");
        java.util.Arrays.sort(words, (a, b) -> Integer.compare(b.length(), a.length()));
        for (String word : words) {
            if (word.length() >= 4) {
                candidates.add(word);
            }
        }
        attemptClick(new java.util.ArrayList<>(candidates), 0, query, callback);
    }

    private void attemptClick(java.util.List<String> candidates, int index,
            String originalQuery, DeviceActions.Callback callback) {
        if (!isReady()) {
            callback.onResult(context().getString(R.string.action_failed));
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && index < candidates.size()) {
            String candidate = candidates.get(index);
            AccessibilityNodeInfo target = findClickableContaining(root, candidate, 0);
            if (target != null) {
                boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                callback.onResult(ok ? context().getString(R.string.action_ok)
                        : context().getString(R.string.action_failed));
                return;
            }
        }
        int next = index + 1;
        if (next <= candidates.size() + 4) {
            // Results can still be rendering: rescan, and after exhausting the
            // word list keep rescanning briefly for late-loading content.
            mainHandler.postDelayed(() -> attemptClick(candidates, next,
                    originalQuery, callback), 800L);
        } else {
            callback.onResult(context().getString(R.string.action_no_match, originalQuery));
        }
    }

    /** Depth-limited search for a clickable node matching the query text. */
    private AccessibilityNodeInfo findClickableContaining(AccessibilityNodeInfo node,
            String query, int depth) {
        if (node == null || depth > 24) {
            return null;
        }
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        boolean match = (text != null && text.toString().toLowerCase(Locale.ROOT).contains(query))
                || (desc != null && desc.toString().toLowerCase(Locale.ROOT).contains(query));
        if (match && (node.isClickable() || node.isScrollable())) {
            // Prefer clicking the clickable ancestor of a matching leaf.
            AccessibilityNodeInfo p = node;
            for (int i = 0; i < 4 && p != null; i++) {
                if (p.isClickable()) {
                    return p;
                }
                p = p.getParent();
            }
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findClickableContaining(child, query, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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
