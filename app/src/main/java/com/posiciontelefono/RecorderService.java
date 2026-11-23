package com.posiciontelefono;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.IBinder;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneId;

/**
 * Foreground service that keeps the GPS on and appends one fix every 10 s to a CSV file.
 * Keeps running with the screen off; stopped from the app or the notification.
 */
public final class RecorderService extends Service implements LocationListener {

    static final String ACTION_START = "com.posiciontelefono.START";
    static final String ACTION_STOP = "com.posiciontelefono.STOP";
    static final long INTERVAL_MS = 10_000;
    /** Asking for 1 fix/s keeps the receiver locked, which gives better fixes than waking it every 10 s. */
    private static final long GPS_REQUEST_MS = 1_000;
    private static final String CHANNEL = "recording";
    private static final int NOTIFICATION_ID = 1;

    // Read by MainActivity; same process, written only on the main thread.
    static volatile boolean recording;
    static volatile File currentFile;
    static volatile int savedPoints;
    static volatile Location lastFix;

    private final Sampler sampler = new Sampler((INTERVAL_MS - GPS_REQUEST_MS / 2) * 1_000_000L);
    private LocationManager locationManager;
    private Writer out;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            stop();
        } else if (!goForeground() || (!recording && !start())) {
            stop();
        }
        return START_NOT_STICKY;
    }

    @SuppressLint("MissingPermission") // checked on the first line
    private boolean start() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        File file = TrackStore.newFile(this, System.currentTimeMillis());
        try {
            out = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
            out.write(TrackPoint.CSV_HEADER + '\n');
            out.flush();
        } catch (IOException e) {
            return false;
        }
        currentFile = file;
        savedPoints = 0;
        lastFix = null;
        locationManager = getSystemService(LocationManager.class);
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_REQUEST_MS, 0f, this, Looper.getMainLooper());
        recording = true;
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
            savedPoints++;
        } catch (IOException e) {
            stop(); // storage full or gone
        }
    }

    private boolean goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RecorderService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stop), getString(R.string.stop), stop).build())
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
        try {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            return true;
        } catch (RuntimeException e) { // e.g. ForegroundServiceStartNotAllowedException
            return false;
        }
    }

    private void stop() {
        release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
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

    @Override
    public void onDestroy() {
        release();
        super.onDestroy();
    }
}
