package com.michaelunkai.zcoderemote;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.KeyEvent;

import java.util.Locale;

/**
 * Central router for every device-control action. The panel calls it directly;
 * the JS bridge calls it after verifying the page origin. Actions that need the
 * accessibility engine are rejected with a hint when it is not enabled.
 */
public final class DeviceActions {

    /** Global action ids from AccessibilityService (kept as ints for bridge use). */
    public static final int GLOBAL_BACK = 1;
    public static final int GLOBAL_HOME = 2;
    public static final int GLOBAL_RECENTS = 3;
    public static final int GLOBAL_NOTIFICATIONS = 4;
    public static final int GLOBAL_QUICK_SETTINGS = 5;
    public static final int GLOBAL_POWER_DIALOG = 6;
    public static final int GLOBAL_SPLIT_SCREEN = 7;
    public static final int GLOBAL_LOCK_SCREEN = 8;

    public interface Callback {
        void onResult(String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private DeviceActions() {
    }

    /** Posts to the main thread and runs; every action funnels through here. */
    private static void run(Context context, Callback callback, Runnable action) {
        Callback safe = callback != null ? callback : (msg) -> { };
        MAIN.post(() -> {
            try {
                action.run();
            } catch (Exception e) {
                safe.onResult(context.getString(R.string.action_failed));
            }
        });
    }

    private static boolean needAccessibility(Context context, Callback callback) {
        if (!ZcodeControlAccessibilityService.isReady()) {
            callback.onResult(context.getString(R.string.action_need_accessibility));
            return true;
        }
        return false;
    }

    public static void globalAction(Context context, int action, Callback callback) {
        run(context, callback, () -> {
            if (needAccessibility(context, callback)) {
                return;
            }
            ZcodeControlAccessibilityService.get().performGlobal(action, callback);
        });
    }

    public static void tap(Context context, int x, int y, Callback callback) {
        run(context, callback, () -> {
            if (needAccessibility(context, callback)) {
                return;
            }
            ZcodeControlAccessibilityService.get().tap(x, y, callback);
        });
    }

    public static void longPress(Context context, int x, int y, Callback callback) {
        run(context, callback, () -> {
            if (needAccessibility(context, callback)) {
                return;
            }
            ZcodeControlAccessibilityService.get().longPress(x, y, callback);
        });
    }

    public static void swipe(Context context, int x1, int y1, int x2, int y2, int ms, Callback callback) {
        run(context, callback, () -> {
            if (needAccessibility(context, callback)) {
                return;
            }
            ZcodeControlAccessibilityService.get().swipe(x1, y1, x2, y2, ms, callback);
        });
    }

    public static void typeText(Context context, String text, Callback callback) {
        run(context, callback, () -> {
            if (needAccessibility(context, callback)) {
                return;
            }
            ZcodeControlAccessibilityService.get().typeText(text, callback);
        });
    }

    public static void screenshot(Context context, Callback callback) {
        run(context, callback, () -> {
            if (needAccessibility(context, callback)) {
                return;
            }
            ZcodeControlAccessibilityService.get().takeScreenshot(callback);
        });
    }

    /** Aliases map a spoken name to a preferred package + a label hint. */
    private static final java.util.Map<String, String[]> APP_ALIASES = java.util.Map.of(
            "youtube", new String[]{"com.google.android.youtube", "youtube"},
            "ytmusic", new String[]{"com.google.android.apps.youtube.music", "yt music"},
            "yt music", new String[]{"com.google.android.apps.youtube.music", "yt music"},
            "music", new String[]{"com.google.android.apps.youtube.music", "music"},
            "spotify", new String[]{"com.spotify.music", "spotify"},
            "gmail", new String[]{"com.google.android.gm", "gmail"},
            "whatsapp", new String[]{"com.whatsapp", "whatsapp"},
            "phone", new String[]{"com.samsung.android.dialer", "phone"},
            "messages", new String[]{"com.samsung.android.messaging", "messages"});

    private static boolean isLaunchable(Context context, String pkg) {
        return pkg != null && context.getPackageManager().getLaunchIntentForPackage(pkg) != null;
    }

    /**
     * Resolves a name/alias/package to an INSTALLED launcher package. ReVanced
     * forks replace stock packages on this device, so aliases never trust a
     * package blindly: the alias is a hint, and among multiple matches the
     * label hint (e.g. "yt music") and mainstream-fork package names decide.
     */
    public static String resolveLauncherPackage(Context context, String nameOrPackage) {
        if (nameOrPackage == null) {
            return null;
        }
        String needle = nameOrPackage.trim().toLowerCase(Locale.ROOT);
        String[] alias = APP_ALIASES.get(needle);
        String aliasPkg = alias == null ? null : alias[0];
        String labelHint = alias == null ? needle : alias[1];
        if (aliasPkg != null && isLaunchable(context, aliasPkg)) {
            return aliasPkg;
        }
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.List<android.content.pm.ResolveInfo> launchers =
                context.getPackageManager().queryIntentActivities(main, 0);
        String flatNeedle = needle.replaceAll("[^a-z0-9]", "");
        String flatAlias = aliasPkg == null ? "" : aliasPkg.replaceAll("[^a-z0-9]", "");
        String best = null;
        int bestScore = -1;
        for (android.content.pm.ResolveInfo info : launchers) {
            String pkg = info.activityInfo.packageName;
            String label = String.valueOf(info.loadLabel(context.getPackageManager()))
                    .toLowerCase(Locale.ROOT);
            String flatPkg = pkg.replaceAll("[^a-z0-9]", "");
            String flatLabel = label.replaceAll("[^a-z0-9]", "");
            boolean exact = pkg.equalsIgnoreCase(needle) || label.equals(needle);
            boolean contains = label.contains(needle) || pkg.contains(needle)
                    || flatLabel.contains(flatNeedle) || flatPkg.contains(flatNeedle)
                    || (flatNeedle.isEmpty() ? false : flatPkg.contains(flatNeedle));
            boolean aliasMatch = flatAlias.length() >= 6
                    && (flatPkg.contains(flatAlias) || flatLabel.contains(flatAlias))
                    && (label.contains(labelHint) || flatLabel.contains(
                        labelHint.replaceAll("[^a-z0-9]", "")));
            if (!exact && !contains && !aliasMatch) {
                continue;
            }
            int score = exact ? 100 : 0;
            String flatLabelText = label.replaceAll("[^a-z0-9]", "");
            if (label.contains(labelHint)) {
                score += 8;
            }
            if (flatLabelText.contains(labelHint.replaceAll("[^a-z0-9]", ""))) {
                score += 6;
            }
            if (pkg.contains("revanced")) {
                score += 3;
            }
            if (pkg.contains("rvx")) {
                score += 2;
            }
            if (pkg.contains("anddea")) {
                score += 1;
            }
            if (score > bestScore) {
                bestScore = score;
                best = pkg;
            }
        }
        return best;
    }

    /** Launches an app by name or package: "youtube" matches label or package id. */
    public static void launchAppByName(Context context, String nameOrPackage, Callback callback) {
        run(context, callback, () -> {
            String resolved = resolveLauncherPackage(context, nameOrPackage);
            if (resolved == null) {
                callback.onResult(context.getString(R.string.action_app_not_found));
                return;
            }
            String launchPkg = resolved;
            MAIN.post(() -> launchApp(context, launchPkg, callback));
        });
    }

    /**
     * Plays/searches media in a specific app: wakes the screen, launches the
     * app, then immediately drives it — Search control, inject query, click
     * the matching result, confirm playback. No deep links: nothing ever
     * opens in a browser and the automation plan is ready before the app
     * finishes loading (no dead wait).
     */
    public static void mediaSearch(Context context, String app, String query, Callback callback) {
        run(context, callback, () -> {
            String nameOrPackage = app == null || app.trim().isEmpty() ? "youtube music" : app;
            if (ZcodeControlAccessibilityService.get() == null) {
                callback.onResult(context.getString(R.string.action_need_accessibility));
                return;
            }
            // Resolve BEFORE launching: unknown app fails fast with nothing opened.
            String pkg = resolveLauncherPackage(context, nameOrPackage);
            if (pkg == null) {
                callback.onResult(context.getString(R.string.action_app_not_found));
                return;
            }
            ZcodeControlAccessibilityService.wakeScreenIfOff(context);
            String q = android.net.Uri.encode(query);
            String url;
            if (pkg.contains("youtube.music")) {
                url = "https://music.youtube.com/search?q=" + q;
            } else {
                url = "https://www.youtube.com/results?search_query=" + q;
            }
            boolean opened;
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                intent.setPackage(pkg);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                opened = true;
            } catch (Exception e) {
                opened = false; // app has no in-app deep link; use the search flow
            }
            if (opened) {
                // Fast path: results render in-app in ~1-2 s; click Play, confirm.
                ZcodeControlAccessibilityService.get().mainHandler().postDelayed(() -> {
                    if (ZcodeControlAccessibilityService.isReady()) {
                        ZcodeControlAccessibilityService.get().deepLinkPlay(pkg, query,
                                () -> searchAndPlayFallback(context, pkg, query, callback),
                                callback);
                    }
                }, 120L);
                return;
            }
            searchAndPlayFallback(context, nameOrPackage, query, callback);
        });
    }

    /** Launch + search-control + typed-query pipeline (the universal fallback). */
    private static void searchAndPlayFallback(Context context, String pkg,
            String query, Callback callback) {
        MAIN.post(() -> {
            if (!ZcodeControlAccessibilityService.isReady()) {
                callback.onResult(context.getString(R.string.action_need_accessibility));
                return;
            }
            launchApp(context, pkg, result -> { });
            ZcodeControlAccessibilityService.get().searchAndPlay(query, callback);
        });
    }

    public static void launchApp(Context context, String packageName, Callback callback) {
        run(context, callback, () -> {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch == null) {
                callback.onResult(context.getString(R.string.action_app_not_found));
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
            callback.onResult(context.getString(R.string.action_ok));
        });
    }

    /** dir: "up" | "down" | "mute" | "unmute" */
    public static void volume(Context context, String dir, Callback callback) {
        run(context, callback, () -> {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) {
                callback.onResult(context.getString(R.string.action_failed));
                return;
            }
            String d = dir == null ? "" : dir.toLowerCase(Locale.ROOT);
            switch (d) {
                case "up":
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                    break;
                case "down":
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                    break;
                case "mute":
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI);
                    break;
                default:
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI);
                    break;
            }
            callback.onResult(context.getString(R.string.action_ok));
        });
    }

    /** key: "play" | "next" | "prev" */
    public static void mediaKey(Context context, String key, Callback callback) {
        run(context, callback, () -> {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) {
                callback.onResult(context.getString(R.string.action_failed));
                return;
            }
            long now = System.currentTimeMillis();
            KeyEvent down;
            String k = key == null ? "play" : key.toLowerCase(Locale.ROOT);
            switch (k) {
                case "next":
                    down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0);
                    break;
                case "prev":
                    down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS, 0);
                    break;
                default:
                    down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0);
                    break;
            }
            audio.dispatchMediaKeyEvent(down);
            audio.dispatchMediaKeyEvent(
                    KeyEvent.changeAction(down, KeyEvent.ACTION_UP));
            callback.onResult(context.getString(R.string.action_ok));
        });
    }

    public static void torch(Context context, boolean on, Callback callback) {
        run(context, callback, () -> {
            try {
                android.hardware.camera2.CameraManager camera =
                        (android.hardware.camera2.CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                if (camera == null) {
                    callback.onResult(context.getString(R.string.action_failed));
                    return;
                }
                String cameraId = null;
                for (String id : camera.getCameraIdList()) {
                    if (camera.getCameraCharacteristics(id)
                            .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == Boolean.TRUE) {
                        cameraId = id;
                        break;
                    }
                }
                if (cameraId == null) {
                    callback.onResult(context.getString(R.string.action_no_torch));
                    return;
                }
                camera.setTorchMode(cameraId, on);
                callback.onResult(context.getString(R.string.action_ok));
            } catch (Exception e) {
                callback.onResult(context.getString(R.string.action_failed));
            }
        });
    }

    /** 1..255; requires the user to grant Write Settings (special access). */
    public static void setBrightness(Context context, int value, Callback callback) {
        run(context, callback, () -> {
            if (!Settings.System.canWrite(context)) {
                callback.onResult(context.getString(R.string.action_need_write_settings));
                return;
            }
            int clamped = Math.max(1, Math.min(255, value));
            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, clamped);
            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            callback.onResult(context.getString(R.string.action_ok));
        });
    }

    public static void readBrightness(Context context, Callback callback) {
        run(context, callback, () -> {
            try {
                int value = Settings.System.getInt(context.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, 128);
                callback.onResult(String.valueOf(value));
            } catch (Exception e) {
                callback.onResult("128");
            }
        });
    }

    public static void wakePc(Context context, Callback callback) {
        run(context, callback, () -> {
            String mac = DevicePrefs.getWakeMac(context);
            if (mac.trim().isEmpty()) {
                callback.onResult(context.getString(R.string.wol_need_mac));
                return;
            }
            new Thread(() -> {
                try {
                    int sent = WakePc.send(mac, DevicePrefs.wakeHost(context),
                            DevicePrefs.wakePort(context), 3);
                    MAIN.post(() -> callback.onResult(context.getString(R.string.wol_sent, sent)));
                } catch (Exception e) {
                    MAIN.post(() -> callback.onResult(
                            context.getString(R.string.wol_failed, e.getMessage())));
                }
            }).start();
        });
    }

    /** Locks the screen using the accessibility global action (API 28+); falls back gracefully. */
    public static void lockScreen(Context context, Callback callback) {
        run(context, callback, () -> {
            if (android.os.Build.VERSION.SDK_INT < 28) {
                callback.onResult(context.getString(R.string.action_failed));
                return;
            }
            if (needAccessibility(context, callback)) {
                return;
            }
            ZcodeControlAccessibilityService.get().performGlobal(GLOBAL_LOCK_SCREEN, callback);
        });
    }

    /** True when the system will not kill the app in the background. */
    public static boolean isIgnoringBatteryOptimizations(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    public static boolean isAccessibilityEnabled(Context context) {
        return ZcodeControlAccessibilityService.isReady();
    }
}
