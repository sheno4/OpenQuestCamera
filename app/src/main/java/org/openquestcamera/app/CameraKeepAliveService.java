package org.openquestcamera.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class CameraKeepAliveService extends Service {
    private static final String CH = "camera_recording";
    private PowerManager.WakeLock lock;

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocale.wrap(newBase));
    }

    @Override public void onCreate() {
        super.onCreate();
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CH, getString(R.string.notification_channel_camera), NotificationManager.IMPORTANCE_LOW));
            promote(false);
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenQuestCamera:service");
            lock.setReferenceCounted(false);
            lock.acquire();
        } catch (Throwable ignored) {}
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try { promote(intent != null && intent.getBooleanExtra("microphone", false)); } catch (Throwable ignored) {}
        return START_STICKY;
    }

    private void promote(boolean microphone) {
        Notification.Builder builder = new Notification.Builder(this, CH);
        Notification notification = builder.setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.notification_recording))
                .setSmallIcon(android.R.drawable.presence_video_online).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 30) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            if (microphone) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(71, notification, type);
        } else {
            startForeground(71, notification);
        }
    }

    @Override public void onDestroy() {
        try { if (lock != null && lock.isHeld()) lock.release(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
