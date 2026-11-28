package com.localcanvas.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Foreground service keeping queued local inference alive within Android background execution limits.
 */
public final class GenerationForegroundService extends Service {
    private static final String CHANNEL_ID = "localcanvas_generation";
    private static final int NOTIF_ID = 1501;

    public static void startIfNeeded(Context ctx, String statusText) {
        Intent i = new Intent(ctx, GenerationForegroundService.class);
        i.putExtra("status", statusText);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, GenerationForegroundService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureChannel();
        String text = intent != null && intent.hasExtra("status")
                ? intent.getStringExtra("status")
                : "로컬 이미지 생성 중...";
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification n = b.setContentTitle("LocalCanvas")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, n);
        return START_NOT_STICKY;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, "이미지 생성 진행", NotificationManager.IMPORTANCE_LOW));
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
