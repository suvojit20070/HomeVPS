package com.suvo.homevps;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.suvo.homevps.linux.LinuxEnv;
import com.suvo.homevps.server.ApiServer;
import com.suvo.homevps.session.SessionManager;
import com.suvo.homevps.util.Prefs;

/**
 * Foreground service that owns the session manager and the API server.
 * Started by the dashboard or, on reboot, by {@link BootReceiver}.
 */
public class VpsService extends Service {

    private static final String TAG = "VpsService";
    private static final String CHANNEL_ID = "home_vps_server";
    private static final int NOTIF_ID = 1001;

    public static final String ACTION_START = "com.suvo.homevps.START";
    public static final String ACTION_STOP = "com.suvo.homevps.STOP";

    public static volatile boolean RUNNING = false;
    public static volatile int PORT = 0;

    private SessionManager sessionManager;
    private ApiServer apiServer;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            stopEverything();
            stopSelf();
            return START_NOT_STICKY;
        }
        startEverything();
        return START_STICKY;
    }

    private void startEverything() {
        if (RUNNING) {
            startForeground(NOTIF_ID, buildNotification());
            return;
        }
        Prefs prefs = new Prefs(this);
        PORT = prefs.getPort();

        startForeground(NOTIF_ID, buildNotification());
        acquireWakeLock();

        // Ubuntu (proot) if installed, otherwise the Android system shell.
        sessionManager = new SessionManager(
                new LinuxEnv(this).currentSpec(),
                prefs.getMaxSessions(),
                prefs.getIdleTimeoutMin());

        apiServer = new ApiServer(prefs.getPort(), prefs.getApiKey(), sessionManager, loadAsset("terminal.html"));
        try {
            apiServer.start();
            RUNNING = true;
            prefs.setDesiredRunning(true);
            Log.i(TAG, "Server started on port " + prefs.getPort());
        } catch (Exception e) {
            Log.e(TAG, "Failed to start server", e);
            stopEverything();
            stopSelf();
        }
        updateNotification();
    }

    private void stopEverything() {
        RUNNING = false;
        new Prefs(this).setDesiredRunning(false);
        if (apiServer != null) {
            apiServer.stop();
            apiServer = null;
        }
        if (sessionManager != null) {
            sessionManager.shutdown();
            sessionManager = null;
        }
        releaseWakeLock();
    }

    private String loadAsset(String name) {
        try (java.io.InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[16 * 1024];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            return buf.toString("UTF-8");
        } catch (Exception e) {
            Log.w(TAG, "Cannot load asset " + name, e);
            return null;
        }
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomeVPS::ServerLock");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Home VPS Server", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps the VPS server running in the background");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, VpsService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        int port = new Prefs(this).getPort();
        String text = RUNNING
                ? "Server running on port " + port
                : "Starting server…";

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            //noinspection deprecation
            b = new Notification.Builder(this);
        }
        b.setContentTitle("Home VPS")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .setContentIntent(openPending)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending);
        return b.build();
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification());
    }

    @Override
    public void onDestroy() {
        stopEverything();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --- convenience launchers -------------------------------------------

    public static void startServer(Context ctx) {
        Intent i = new Intent(ctx, VpsService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stopServer(Context ctx) {
        Intent i = new Intent(ctx, VpsService.class).setAction(ACTION_STOP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }
}
