package com.michaelunkai.zcoderemote;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

/**
 * "Hey ZCode" — an opt-in, battery-aware always-listening service. It keeps a
 * single on-device speech recognizer warm, scans partial results for the wake
 * phrase, and pops the session open instantly. A watchdog recreates the
 * recognizer if the engine stalls; no wake locks are held — the recognizer
 * service manages its own audio path.
 */
public class HotwordService extends Service implements RecognitionListener {

    private static final String TAG = "ZcodeHotword";
    private static final int NOTIFICATION_ID = 42;
    private static final long TRIGGER_DEBOUNCE_MS = 5000L;
    private static final long WATCHDOG_INTERVAL_MS = 10000L;

    private SpeechRecognizer recognizer;
    private Handler handler;
    private volatile long lastCallbackAt;
    private volatile long lastTriggerAt;
    private boolean running;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            if (System.currentTimeMillis() - lastCallbackAt > WATCHDOG_INTERVAL_MS * 2
                    && recognizer != null) {
                recreateRecognizer();
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    private final Runnable restart = this::startListening;

    /** Starts the service when the user opted in and mic permission is granted. */
    public static void startIfEnabled(android.content.Context context) {
        if (!DevicePrefs.isHotwordEnabled(context)
                || !DeviceActions.hasMicPermission(context)
                || !SpeechRecognizer.isRecognitionAvailable(context)) {
            return;
        }
        Intent intent = new Intent(context, HotwordService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(android.content.Context context) {
        context.stopService(new Intent(context, HotwordService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        SessionKeepService.createChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!DevicePrefs.isHotwordEnabled(this) || !DeviceActions.hasMicPermission(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        if (!running) {
            running = true;
            createRecognizer();
            startListening();
            handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, SessionKeepService.CHANNEL_LIVE);
        } else {
            builder = new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        }
        return builder
                .setSmallIcon(R.drawable.ic_shortcut_link)
                .setContentTitle(getString(R.string.notif_hotword_title))
                .setContentText(getString(R.string.notif_hotword_text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void createRecognizer() {
        destroyRecognizer();
        // Prefer the on-device recognizer (API 31+): no network round trip and it
        // does not play the per-cycle "ready" beep the network recognizer plays
        // on some OEMs (Samsung). Fall back to the standard recognizer.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            } catch (Exception e) {
                recognizer = null;
            }
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        }
        recognizer.setRecognitionListener(this);
        lastCallbackAt = System.currentTimeMillis();
    }

    private void recreateRecognizer() {
        Log.i(TAG, "recognizer stalled — recreating");
        createRecognizer();
        startListening();
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }
    }

    private void startListening() {
        if (recognizer == null || !running) {
            return;
        }
        handler.removeCallbacks(restart);
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        try {
            recognizer.startListening(intent);
            lastCallbackAt = System.currentTimeMillis();
        } catch (Exception e) {
            Log.w(TAG, "startListening failed; retrying", e);
            handler.postDelayed(restart, 2000L);
        }
    }

    private void handleUtterance(ArrayList<String> candidates) {
        if (candidates == null) {
            return;
        }
        for (String candidate : candidates) {
            if (candidate != null && HotwordMatcher.matches(candidate)) {
                trigger();
                return;
            }
        }
    }

    private void trigger() {
        long now = System.currentTimeMillis();
        if (now - lastTriggerAt < TRIGGER_DEBOUNCE_MS) {
            return;
        }
        lastTriggerAt = now;
        Log.i(TAG, "wake phrase detected — opening session");
        SessionLauncher.openSession(this);
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
        lastCallbackAt = System.currentTimeMillis();
    }

    @Override
    public void onBeginningOfSpeech() {
        lastCallbackAt = System.currentTimeMillis();
    }

    @Override
    public void onRmsChanged(float rmsdB) {
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
    }

    @Override
    public void onEndOfSpeech() {
        lastCallbackAt = System.currentTimeMillis();
    }

    @Override
    public void onError(int error) {
        lastCallbackAt = System.currentTimeMillis();
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            DevicePrefs.setHotwordEnabled(this, false);
            stopSelf();
            return;
        }
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                || error == SpeechRecognizer.ERROR_CLIENT) {
            recreateRecognizer();
        } else {
            // NO_MATCH / SPEECH_TIMEOUT / NETWORK_* / SERVER: the normal idle loop.
            handler.removeCallbacks(restart);
            handler.postDelayed(restart, 250L);
        }
    }

    @Override
    public void onResults(Bundle results) {
        lastCallbackAt = System.currentTimeMillis();
        handleUtterance(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
        handler.removeCallbacks(restart);
        handler.postDelayed(restart, 300L);
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        lastCallbackAt = System.currentTimeMillis();
        handleUtterance(partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        destroyRecognizer();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
