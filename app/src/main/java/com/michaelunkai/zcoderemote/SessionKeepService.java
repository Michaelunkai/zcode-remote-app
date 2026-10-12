package com.michaelunkai.zcoderemote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Keep-alive foreground service. Its only job is to hold the process (and
 * therefore the live WebView page) above cached-process eviction while the app
 * is minimized, so returning to the app — or switching session tabs — always
 * lands on a live, already-updated page in well under a second. No wake locks,
 * no polling: the cost is one silent notification, nothing more.
 */
public class SessionKeepService extends Service {

    public static final String CHANNEL_LIVE = "session_live";
    public static final String CHANNEL_READY = "session_ready";

    private static final int NOTIFICATION_ID = 40;

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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            DevicePrefs.setKeepAliveEnabled(this, false);
            stopSelf();
            return START_NOT_STICKY;
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
