package com.michaelunkai.zcoderemote;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Opens the live session from any entry point (hotword, assistant, tile,
 * notification). Background activity starts are restricted on modern Android,
 * so this tries the direct launch first and falls back to a full-screen-intent
 * notification — same instant behavior, system-sanctioned delivery.
 */
public final class SessionLauncher {

    private static final int READY_NOTIFICATION_ID = 41;

    private SessionLauncher() {
    }

    public static void openSession(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            showReadyNotification(context);
        }
    }

    /** Full-screen-intent fallback: wakes the screen and shows a tap-to-open card. */
    private static void showReadyNotification(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context, READY_NOTIFICATION_ID, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder builder = new Notification.Builder(context, SessionKeepService.CHANNEL_READY)
                    .setSmallIcon(R.drawable.ic_shortcut_link)
                    .setContentTitle(context.getString(R.string.notif_ready_title))
                    .setContentText(context.getString(R.string.notif_ready_text))
                    .setContentIntent(contentIntent)
                    .setFullScreenIntent(contentIntent, true)
                    .setOngoing(true);
            notification = builder.build();
        } else {
            notification = new Notification.Builder(context)
                    .setSmallIcon(R.drawable.ic_shortcut_link)
                    .setContentTitle(context.getString(R.string.notif_ready_title))
                    .setContentText(context.getString(R.string.notif_ready_text))
                    .setContentIntent(contentIntent)
                    .setPriority(Notification.PRIORITY_MAX)
                    .setFullScreenIntent(contentIntent, true)
                    .setOngoing(true)
                    .build();
        }
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(READY_NOTIFICATION_ID, notification);
        }
    }
}
