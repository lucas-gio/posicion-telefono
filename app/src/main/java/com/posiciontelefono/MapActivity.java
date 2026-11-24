package com.posiciontelefono;

import android.app.Activity;
import android.net.http.HttpResponseCache;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MapActivity extends Activity {

    static final String EXTRA_FILE = "file";
    private static final long TILE_DISK_CACHE_BYTES = 50L << 20;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable liveRefresh = new Runnable() {
        @Override
        public void run() {
            load(false);
            main.postDelayed(this, RecorderService.INTERVAL_MS);
        }
    };

    private File file;
    private TrackMapView map;
    private TextView info;
    private String summary = "";

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        file = TrackStore.find(this, getIntent().getStringExtra(EXTRA_FILE));
        if (file == null) {
            finish();
            return;
        }
        if (HttpResponseCache.getInstalled() == null) {
            try {
                HttpResponseCache.install(new File(getCacheDir(), "tiles"), TILE_DISK_CACHE_BYTES);
            } catch (IOException ignored) {
                // works without a disk cache, just downloads more
            }
        }
        setContentView(R.layout.activity_map);
        setTitle(TrackStore.title(this, file));
        map = findViewById(R.id.map);
        info = findViewById(R.id.info);
        map.setOnSelect(p -> info.setText(p == null ? summary : describe(p)));
        findViewById(R.id.rename).setOnClickListener(v ->
                TrackDialogs.rename(this, file, () -> setTitle(TrackStore.title(this, file))));
        findViewById(R.id.export).setOnClickListener(v -> export());
        findViewById(R.id.delete).setOnClickListener(v -> TrackDialogs.delete(this, file, this::finish));
        load(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean live = isLive();
        findViewById(R.id.delete).setVisibility(live ? View.GONE : View.VISIBLE);
        if (live) main.postDelayed(liveRefresh, RecorderService.INTERVAL_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(liveRefresh);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        if (map != null) map.release();
        super.onDestroy();
    }

    private boolean isLive() {
        return file.equals(RecorderService.currentFile);
    }

    /** Reads the track in the background; only the first load moves the camera. */
    private void load(boolean fit) {
        io.execute(() -> {
            List<TrackPoint> points = TrackStore.read(file);
            String text = points.isEmpty() ? getString(R.string.empty_track)
                    : getString(R.string.track_summary,
                            Format.distance(Track.lengthM(points)), Format.duration(Track.durationMs(points)));
            main.post(() -> {
                summary = isLive() ? getString(R.string.map_live, text) : text;
                info.setText(summary);
                map.setTrack(points, fit);
            });
        });
    }

    private String describe(TrackPoint p) {
        String time = TIME.format(Instant.ofEpochMilli(p.timeMs).atZone(ZoneId.systemDefault()));
        String accuracy = Float.isNaN(p.accuracyM) ? "?" : String.format(Locale.ROOT, "%.0f", p.accuracyM);
        String speed = Float.isNaN(p.speedKmh) ? "?" : String.format(Locale.ROOT, "%.1f", p.speedKmh);
        return getString(R.string.point_info, time, accuracy, speed);
    }

    private void export() {
        io.execute(() -> {
            int message;
            try {
                TrackStore.exportToDownloads(this, file);
                message = R.string.exported;
            } catch (IOException e) {
                message = R.string.export_failed;
            }
            int m = message;
            main.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        });
    }
}
