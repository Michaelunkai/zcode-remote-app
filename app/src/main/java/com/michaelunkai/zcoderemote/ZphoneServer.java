package com.michaelunkai.zcoderemote;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The agent bridge: a small, token-authenticated HTTP endpoint that lets the
 * PC-side agent (via the zphone helper) drive this phone through the exact
 * same DeviceActions engine the panel uses — launch apps, search/play media,
 * tap/swipe/type, navigate, screenshot, and report status. Runs only while the
 * keep-alive service is alive, on the local network, protected by a 32-hex
 * bearer token generated on-device. Discovery from the PC is a cached-host +
 * subnet scan (the helper), so no service-registration dependency is needed.
 */
public class ZphoneServer {

    private static final String TAG = "ZphoneBridge";
    private static ServerSocket serverSocket;
    private static Thread acceptThread;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private interface Action {
        void run(DeviceActions.Callback callback);
    }

    public static synchronized void start(Context context) {
        if (!DevicePrefs.isBridgeEnabled(context) || acceptThread != null) {
            return;
        }
        int port = DevicePrefs.getBridgePort(context);
        try {
            serverSocket = new ServerSocket(port, 64, InetAddress.getByName("0.0.0.0"));
            acceptThread = new Thread(() -> acceptLoop(context.getApplicationContext()),
                    "zphone-bridge");
            acceptThread.setDaemon(true);
            acceptThread.start();
            Log.i(TAG, "agent bridge listening on :" + port);
        } catch (Exception e) {
            Log.w(TAG, "bridge start failed", e);
            stop();
        }
    }

    public static synchronized void stop() {
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
        }
        serverSocket = null;
        acceptThread = null;
    }

    public static boolean isRunning() {
        return acceptThread != null;
    }

    private static void acceptLoop(Context context) {
        while (serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                handle(context, socket);
            } catch (Exception e) {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    Log.w(TAG, "accept error", e);
                }
            }
        }
    }

    private static void handle(Context context, Socket socket) {
        try {
            socket.setSoTimeout(20000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                socket.close();
                return;
            }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";
            int contentLength = 0;
            String auth = "";
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                } else if (lower.startsWith("authorization:")) {
                    auth = line.substring(14).trim();
                }
            }
            StringBuilder body = new StringBuilder();
            if (contentLength > 0 && contentLength < 65536) {
                char[] buffer = new char[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int n = reader.read(buffer, read, contentLength - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
                body.append(buffer, 0, read);
            }

            String token = DevicePrefs.getBridgeToken(context);
            String response;
            if (!auth.equals("Bearer " + token)) {
                response = "{\"status\":\"error\",\"message\":\"unauthorized\"}";
            } else if ("/status".equals(path)) {
                response = statusJson(context);
            } else if ("/exec".equals(path) && "POST".equals(method)) {
                response = exec(context, body.toString());
            } else if ("/shot.png".equals(path)) {
                serveScreenshot(context, socket);
                return;
            } else {
                response = "{\"status\":\"error\",\"message\":\"not found\"}";
            }
            OutputStream out = socket.getOutputStream();
            byte[] payload = response.getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + payload.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(payload);
            out.flush();
            socket.close();
        } catch (Exception e) {
            Log.w(TAG, "request error", e);
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Streams a fresh screenshot as PNG (agent can actually see the phone). */
    private static void serveScreenshot(Context context, Socket socket) {
        try {
            ZcodeControlAccessibilityService service = ZcodeControlAccessibilityService.get();
            if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                byte[] payload = "{\"status\":\"error\"}".getBytes(StandardCharsets.UTF_8);
                socket.getOutputStream().write(("HTTP/1.1 503 Service Unavailable\r\n"
                        + "Content-Length: " + payload.length + "\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().write(payload);
                socket.close();
                return;
            }
            CountDownLatch latch = new CountDownLatch(1);
            final byte[][] png = {null};
            service.capturePng(pngBytes -> {
                png[0] = pngBytes;
                latch.countDown();
            });
            latch.await(8, TimeUnit.SECONDS);
            OutputStream out = socket.getOutputStream();
            if (png[0] == null) {
                byte[] payload = "{\"status\":\"error\"}".getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 500 Internal Server Error\r\n"
                        + "Content-Length: " + payload.length + "\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(payload);
            } else {
                out.write(("HTTP/1.1 200 OK\r\n"
                        + "Content-Type: image/png\r\n"
                        + "Content-Length: " + png[0].length + "\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(png[0]);
            }
            out.flush();
            socket.close();
        } catch (Exception e) {
            Log.w(TAG, "screenshot error", e);
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ---- handlers ----

    private static String statusJson(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        BatteryManager battery = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        int level = battery == null ? -1
                : battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        return "{\"status\":\"ok\",\"device\":\"" + j(Build.MODEL) + "\""
                + ",\"android\":\"" + j(Build.VERSION.RELEASE) + "\""
                + ",\"app\":\"" + j(appVersion(context)) + "\""
                + ",\"battery\":" + level
                + ",\"screen_on\":" + (power != null && power.isInteractive())
                + ",\"locked\":" + keyguardLocked(context)
                + ",\"volume\":" + (audio != null
                        ? audio.getStreamVolume(AudioManager.STREAM_MUSIC) : -1)
                + ",\"foreground\":\"" + j(ZcodeControlAccessibilityService.getForegroundPackage()) + "\""
                + ",\"accessibility\":" + ZcodeControlAccessibilityService.isReady()
                + "}";
    }

    private static boolean keyguardLocked(Context context) {
        android.app.KeyguardManager km =
                (android.app.KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.inKeyguardRestrictedInputMode();
    }

    private static String appVersion(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** Executes one action; the HTTP response carries the action's real result. */
    private static String exec(Context context, String body) {
        String action = str(body, "action");
        if (action == null || action.isEmpty()) {
            return "{\"status\":\"error\",\"message\":\"missing action\"}";
        }
        switch (action) {
            case "launch_app": {
                String target = str(body, "app");
                if (target == null || target.trim().isEmpty()) {
                    return "{\"status\":\"error\",\"message\":\"missing app\"}";
                }
                return sync(callback ->
                        DeviceActions.launchAppByName(context, target, callback));
            }
            case "open_url": {
                String url = str(body, "url");
                if (url == null || url.trim().isEmpty()) {
                    return "{\"status\":\"error\",\"message\":\"missing url\"}";
                }
                return sync(callback -> MAIN.post(() -> {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW,
                                android.net.Uri.parse(url));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(intent);
                        callback.onResult(context.getString(R.string.action_ok));
                    } catch (Exception e) {
                        callback.onResult(context.getString(R.string.action_failed));
                    }
                }));
            }
            case "media_search":
            case "song": {
                String query = str(body, "query");
                if (query == null || query.trim().isEmpty()) {
                    return "{\"status\":\"error\",\"message\":\"missing query\"}";
                }
                return sync(callback ->
                        DeviceActions.mediaSearch(context, str(body, "app"), query, callback));
            }
            case "tap":
                return sync(callback -> DeviceActions.tap(context,
                        num(body, "x"), num(body, "y"), callback));
            case "swipe":
                return sync(callback -> DeviceActions.swipe(context,
                        num(body, "x1"), num(body, "y1"), num(body, "x2"), num(body, "y2"),
                        num(body, "ms"), callback));
            case "text":
                return sync(callback -> DeviceActions.typeText(context,
                        str(body, "text"), callback));
            case "global": {
                Integer id = mapNav(str(body, "where"));
                if (id == null) {
                    return "{\"status\":\"error\",\"message\":\"unknown target\"}";
                }
                return sync(callback -> DeviceActions.globalAction(context, id, callback));
            }
            case "screenshot":
                return sync(callback -> DeviceActions.screenshot(context, callback));
            case "volume":
                return sync(callback -> DeviceActions.volume(context, str(body, "dir"), callback));
            case "media_key":
                return sync(callback -> DeviceActions.mediaKey(context, str(body, "key"), callback));
            case "torch":
                return sync(callback -> DeviceActions.torch(context,
                        "on".equalsIgnoreCase(str(body, "state")), callback));
            case "brightness":
                return sync(callback -> DeviceActions.setBrightness(context,
                        num(body, "value"), callback));
            case "foreground": {
                String pkg = ZcodeControlAccessibilityService.getForegroundPackage();
                return "{\"status\":\"ok\",\"foreground\":\"" + j(pkg) + "\"}";
            }
            default:
                return "{\"status\":\"error\",\"message\":\"unknown action\"}";
        }
    }

    /** Runs the action on the main thread and waits (max 12 s) for its result. */
    private static String sync(Action action) {
        CountDownLatch latch = new CountDownLatch(1);
        final String[] result = {"{\"status\":\"ok\",\"result\":\"accepted\"}"};
        DeviceActions.Callback callback = message -> {
            result[0] = "{\"status\":\"ok\",\"result\":\"" + j(message) + "\"}";
            latch.countDown();
        };
        MAIN.post(() -> {
            try {
                action.run(callback);
            } catch (Exception e) {
                result[0] = "{\"status\":\"error\",\"message\":\"" + j(String.valueOf(e)) + "\"}";
                latch.countDown();
            }
        });
        MAIN.postDelayed(latch::countDown, 19500L);
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return result[0];
    }

    private static Integer mapNav(String where) {
        if (where == null) {
            return null;
        }
        switch (where.toLowerCase(Locale.ROOT)) {
            case "back": return DeviceActions.GLOBAL_BACK;
            case "home": return DeviceActions.GLOBAL_HOME;
            case "recents": return DeviceActions.GLOBAL_RECENTS;
            case "notifications": return DeviceActions.GLOBAL_NOTIFICATIONS;
            case "quicksettings": return DeviceActions.GLOBAL_QUICK_SETTINGS;
            case "power": return DeviceActions.GLOBAL_POWER_DIALOG;
            default: return null;
        }
    }

    // ---- tiny flat-JSON extraction (flat string/number values only; UTF-8 body) ----

    private static String str(String json, String key) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private static int num(String json, String key) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(-?[0-9]+)")
                .matcher(json);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static String j(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    /** Best-effort WiFi IPv4 address for the panel display. */
    public static String wifiAddress() {
        try {
            for (Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces(); interfaces.hasMoreElements();) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                for (InetAddress address : java.util.Collections
                        .list(networkInterface.getInetAddresses())) {
                    if (!address.isLoopbackAddress() && address.getAddress().length == 4) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
