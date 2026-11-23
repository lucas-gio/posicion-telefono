package com.posiciontelefono;

import android.app.Activity;
import android.net.http.HttpResponseCache;
import android.os.Bundle;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public final class MapActivity extends Activity {

    static final String EXTRA_FILE = "file";
    private static final long TILE_DISK_CACHE_BYTES = 50L << 20;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    private TrackMapView map;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        File file = TrackStore.find(this, getIntent().getStringExtra(EXTRA_FILE));
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
        setTitle(Track.title(file.getName()));
        map = findViewById(R.id.map);
        TextView info = findViewById(R.id.info);

        new Thread(() -> {
            List<TrackPoint> points = TrackStore.read(file);
            String summary = points.isEmpty() ? getString(R.string.empty_track)
                    : getString(R.string.map_summary, points.size(), Track.lengthM(points) / 1000);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                info.setText(summary);
                map.setTrack(points);
                map.setOnSelect(p -> info.setText(p == null ? summary : describe(p)));
            });
        }, "read-track").start();
    }

    private String describe(TrackPoint p) {
        String time = TIME.format(Instant.ofEpochMilli(p.timeMs).atZone(ZoneId.systemDefault()));
        String accuracy = Float.isNaN(p.accuracyM) ? "?" : String.format(Locale.ROOT, "%.0f", p.accuracyM);
        String speed = Float.isNaN(p.speedKmh) ? "?" : String.format(Locale.ROOT, "%.1f", p.speedKmh);
        return getString(R.string.point_info, time, accuracy, speed);
    }

    @Override
    protected void onDestroy() {
        if (map != null) map.release();
        super.onDestroy();
    }
}
