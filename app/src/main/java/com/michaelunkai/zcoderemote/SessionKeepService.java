package com.michaelunkai.zcoderemote;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Keep-alive foreground service. Its only job is to hold the process (and
 * therefore the live WebView page) above cached-process eviction while the app
 * is minimized, so returning to the app — or switching session tabs — always
 * lands on a live, already-updated page in well under a second. No wake locks,
 * no polling: the cost is one silent notification, nothing more.
 *
 * 20x-LAUNCH LAW (owner directive 2026-10-25): a cold open pays the ~10 s
 * server-side relay handshake; a warm adopt measures 18-98 ms. The service
 * therefore keeps the warm state alive by ANY legal means: a self-reviving
 * alarm every ~12 min (survives Device-Care kills — they are not
 * force-stops) and an on-screen-on page check, so the page is virtually
 * always already connected before the owner ever opens the app.
 */
public class SessionKeepService extends Service {

    public static final String CHANNEL_LIVE = "session_live";
    public static final String CHANNEL_READY = "session_ready";

    private static final int NOTIFICATION_ID = 40;
    private static final long REVIVE_ALARM_MS = 12 * 60 * 1000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    /** Re-pins the offscreen keep-visible window if the system dropped it. */
    private final Runnable overlayWatchdog = new Runnable() {
        @Override
        public void run() {
            SessionWebViewHolder.ensureOverlayAlive();
            main.postDelayed(this, 60000L);
        }
    };

    /**
     * The moment the screen lights up (the prelude to the owner opening the
     * app), make sure the session page exists and is connecting/connected —
     * the handshake starts NOW, not after the icon tap.
     */
    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String url = new UrlStore(context).get();
            if (!url.isEmpty()) {
                SessionWebViewHolder.ensureLoaded(context, url);
            }
        }
    };

    /**
     * Chains a allow-while-idle alarm that re-runs the PREWARM broadcast:
     * if the process was killed (Device Care, memory pressure), it revives
     * within ~12 minutes and rebuilds the connected offscreen page — the
     * "cold open pays 10 s" window shrinks from forever to at most 12 min,
     * and only after an actual system kill.
     */
    private void scheduleRevive() {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        Intent fire = new Intent(this, RestartReceiver.class);
        fire.setAction(RestartReceiver.ACTION_PREWARM);
        PendingIntent pi = PendingIntent.getBroadcast(
                this, 41, fire, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + REVIVE_ALARM_MS, pi);
    }

    /** Starts (or refreshes the heartbeat of) the keep-alive service if enabled. */
    public static void start(Context context) {
        if (!DevicePrefs.isKeepAliveEnabled(context)) {
            return;
        }
        Intent intent = new Intent(context, SessionKeepService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, SessionKeepService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels(this);
        ZphoneServer.start(this);
        main.post(overlayWatchdog);
        registerReceiverSafe();
    }

    private void registerReceiverSafe() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON),
                        Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
            }
        } catch (Exception e) {
            // Optimization only — the revive alarm still covers cold starts.
        }
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(overlayWatchdog);
        try {
            unregisterReceiver(screenOn);
        } catch (IllegalArgumentException ignored) {
        }
        ZphoneServer.stop();
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            DevicePrefs.setKeepAliveEnabled(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        ZphoneServer.start(this);
        scheduleRevive();
        // Boot/kill prewarm: hold an offscreen, already-connecting session
        // page so the first open after a reboot adopts it instantly (<2 s)
        // instead of paying the ~9 s server-side relay handshake.
        String url = new UrlStore(this).get();
        if (!url.isEmpty()) {
            SessionWebViewHolder.ensureLoadedWhenNetworkUp(this, url);
        }
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(
                this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent panel = new Intent(this, DevicePanelActivity.class);
        PendingIntent panelIntent = PendingIntent.getActivity(
                this, 2, panel, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, SessionKeepService.class);
        stop.setAction("STOP");
        PendingIntent stopIntent = PendingIntent.getService(
                this, 3, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String when = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_LIVE);
        } else {
            builder = new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        }
        return builder
                .setSmallIcon(R.drawable.ic_shortcut_link)
                .setContentTitle(getString(R.string.notif_live_title))
                .setContentText(getString(R.string.notif_live_text, when))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openIntent)
                .addAction(0, getString(R.string.notif_action_panel), panelIntent)
                .addAction(0, getString(R.string.notif_action_stop), stopIntent)
                .build();
    }

    static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel live = new NotificationChannel(CHANNEL_LIVE,
                context.getString(R.string.channel_live_name),
                NotificationManager.IMPORTANCE_LOW);
        live.setDescription(context.getString(R.string.channel_live_desc));
        live.setShowBadge(false);
        nm.createNotificationChannel(live);

        NotificationChannel ready = new NotificationChannel(CHANNEL_READY,
                context.getString(R.string.channel_ready_name),
                NotificationManager.IMPORTANCE_HIGH);
        ready.setDescription(context.getString(R.string.channel_ready_desc));
        nm.createNotificationChannel(ready);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
