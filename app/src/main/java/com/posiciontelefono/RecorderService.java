package com.posiciontelefono;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.service.quicksettings.TileService;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneId;

/**
 * Foreground service that keeps the GPS on and appends one fix every 10 s to a CSV file.
 * Keeps running with the screen off or locked; its notification is visible on the lock screen
 * with a live timer and a "Terminar" button.
 */
public final class RecorderService extends Service implements LocationListener {

    private static final String ACTION_START = "com.posiciontelefono.START";
    private static final String ACTION_STOP = "com.posiciontelefono.STOP";
    static final long INTERVAL_MS = 10_000;
    /** Asking for 1 fix/s keeps the receiver locked, which gives better fixes than waking it every 10 s. */
    private static final long GPS_REQUEST_MS = 1_000;
    /** A fix older than this means the GPS lost the sky (tunnel, building). */
    static final long STALE_FIX_MS = 15_000;
    /** v2: default importance, so the notification also shows on the lock screen (silent ones are hidden there). */
    private static final String CHANNEL = "recording_v2";
    private static final String OLD_CHANNEL = "recording";
    private static final int NOTIFICATION_ID = 1;

    // Read by the UI; same process, written only on the main thread.
    static volatile boolean recording;
    static volatile File currentFile;
    static volatile int savedPoints;
    static volatile double distanceM;
    static volatile long startedElapsedMs;
    static volatile Location lastFix;

    private final Sampler sampler = new Sampler((INTERVAL_MS - GPS_REQUEST_MS / 2) * 1_000_000L);
    private Odometer odometer;
    private LocationManager locationManager;
    private NotificationManager notifications;
    private Writer out;
    private long startedWallMs;

    /** Must be called while the app is visible: only then does Android give the service GPS access. */
    static void start(Context c) {
        c.startForegroundService(new Intent(c, RecorderService.class).setAction(ACTION_START));
    }

    static void stop(Context c) {
        c.startService(new Intent(c, RecorderService.class).setAction(ACTION_STOP));
    }

    /** "GPS bueno (±4 m)", "Buscando satélites…" or "Sin señal de GPS"; same text in the app and the notification. */
    static String gpsStatus(Context c, Location fix) {
        if (fix == null) return c.getString(R.string.gps_searching);
        long ageMs = (SystemClock.elapsedRealtimeNanos() - fix.getElapsedRealtimeNanos()) / 1_000_000L;
        if (ageMs > STALE_FIX_MS) return c.getString(R.string.gps_lost);
        int[] labels = {R.string.gps_good, R.string.gps_fair, R.string.gps_poor};
        return c.getString(labels[TrackPoint.quality(fix.getAccuracy())], fix.getAccuracy());
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notifications = getSystemService(NotificationManager.class);
        notifications.deleteNotificationChannel(OLD_CHANNEL);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT);
        channel.setSound(null, null); // visible, but never makes noise
        channel.enableVibration(false);
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        notifications.createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            stopRecording();
        } else if (recording) {
            goForeground(); // a repeated start must still satisfy startForegroundService()
        } else if (!startRecording()) {
            stopRecording();
        }
        return START_NOT_STICKY;
    }

    @SuppressLint("MissingPermission") // checked on the first line
    private boolean startRecording() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        startedWallMs = System.currentTimeMillis();
        startedElapsedMs = SystemClock.elapsedRealtime();
        if (!goForeground()) return false;
        File file = TrackStore.newFile(this, startedWallMs);
        try {
            out = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
            out.write(TrackPoint.CSV_HEADER + '\n');
            out.flush();
        } catch (IOException e) {
            return false;
        }
        currentFile = file;
        savedPoints = 0;
        distanceM = 0;
        lastFix = null;
        odometer = new Odometer();
        locationManager = getSystemService(LocationManager.class);
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_REQUEST_MS, 0f, this, Looper.getMainLooper());
        recording = true;
        refreshTile();
        return true;
    }

    @Override
    public void onLocationChanged(Location loc) {
        lastFix = loc;
        if (out == null || !sampler.accept(loc.getElapsedRealtimeNanos())) return;
        TrackPoint p = new TrackPoint(loc.getTime(), loc.getLatitude(), loc.getLongitude(),
                loc.hasAccuracy() ? loc.getAccuracy() : Float.NaN,
                loc.hasSpeed() ? loc.getSpeed() * 3.6f : Float.NaN);
        try {
            out.write(p.toCsv(ZoneId.systemDefault()) + '\n');
            out.flush(); // on disk right away: nothing is lost if the phone dies
        } catch (IOException e) {
            stopRecording(); // storage full or gone
            return;
        }
        savedPoints++;
        odometer.add(p);
        distanceM = odometer.meters();
        notifications.notify(NOTIFICATION_ID, buildNotification());
    }

    private boolean goForeground() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            return true;
        } catch (RuntimeException e) { // e.g. ForegroundServiceStartNotAllowedException
            return false;
        }
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RecorderService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        String text = recording
                ? getString(R.string.notif_text, Format.distance(distanceM), gpsStatus(this, lastFix))
                : getString(R.string.gps_searching);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setWhen(startedWallMs)
                .setUsesChronometer(true) // live timer drawn by the system, no updates needed
                .setShowWhen(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                // Shows distance and GPS state on the lock screen; coordinates are never in the notification.
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_WORKOUT)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stop), getString(R.string.finish), stop)
                        .setAuthenticationRequired(false) // "Terminar" works on the lock screen without the PIN
                        .build())
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    private void stopRecording() {
        release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        refreshTile(); // also undoes the tile's optimistic "on" if starting failed
    }

    private void release() {
        recording = false;
        currentFile = null;
        if (locationManager != null) locationManager.removeUpdates(this);
        locationManager = null;
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // every row was already flushed
            }
            out = null;
        }
    }

    private void refreshTile() {
        TileService.requestListeningState(this, new ComponentName(this, RecordTileService.class));
    }

    @Override
    public void onDestroy() {
        release();
        super.onDestroy();
    }
}
