package com.posiciontelefono;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {

    private static final int PERMISSIONS_REQUEST = 1;
    /** Summaries by "name:size", so unchanged tracks are not re-read on every resume. */
    private static final Map<String, String> SUMMARIES = new ConcurrentHashMap<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            main.postDelayed(this, 1000);
        }
    };

    private TextView status, detail;
    private Button toggle;
    private View battery;
    private ArrayAdapter<File> adapter;
    private boolean wasRecording;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        detail = findViewById(R.id.detail);
        toggle = findViewById(R.id.toggle);
        battery = findViewById(R.id.battery);
        toggle.setOnClickListener(v -> toggle(true));
        findViewById(R.id.battery_allow).setOnClickListener(v -> askUnrestrictedBattery());

        adapter = new ArrayAdapter<File>(this, R.layout.item_track, R.id.title) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                File f = getItem(position);
                ((TextView) v.findViewById(R.id.title)).setText(Track.title(f.getName()));
                String summary = f.equals(RecorderService.currentFile)
                        ? getString(R.string.recording_now) : SUMMARIES.get(key(f));
                ((TextView) v.findViewById(R.id.summary)).setText(summary != null ? summary : "…");
                return v;
            }
        };
        ListView list = findViewById(R.id.list);
        list.setEmptyView(findViewById(R.id.empty));
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, i, id) -> openMap(adapter.getItem(i)));
        list.setOnItemLongClickListener((p, v, i, id) -> {
            showOptions(adapter.getItem(i));
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean unrestricted = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        battery.setVisibility(unrestricted ? View.GONE : View.VISIBLE);
        reloadList();
        main.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    @SuppressLint("BatteryLife") // a GPS logger is an accepted use case, and the APK is sideloaded
    private void askUnrestrictedBattery() {
        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName())));
    }

    private void toggle(boolean mayAskPermissions) {
        if (RecorderService.recording) {
            startService(new Intent(this, RecorderService.class).setAction(RecorderService.ACTION_STOP));
            main.postDelayed(this::reloadList, 300);
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (mayAskPermissions) {
                String[] wanted = Build.VERSION.SDK_INT >= 33
                        ? new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS}
                        : new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION};
                requestPermissions(wanted, PERMISSIONS_REQUEST);
            } else {
                explain(R.string.need_precise, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
            return;
        }
        if (!getSystemService(LocationManager.class).isLocationEnabled()) {
            explain(R.string.enable_location, new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        startForegroundService(new Intent(this, RecorderService.class).setAction(RecorderService.ACTION_START));
        main.postDelayed(this::reloadList, 300);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code == PERMISSIONS_REQUEST) toggle(false);
    }

    private void explain(int message, Intent settings) {
        new AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton(R.string.open_settings, (d, w) -> startActivity(settings))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void render() {
        boolean rec = RecorderService.recording;
        toggle.setText(rec ? R.string.stop : R.string.start);
        toggle.setBackgroundTintList(getColorStateList(rec ? R.color.stop : R.color.start));
        status.setText(rec ? R.string.recording : R.string.idle);
        Location fix = RecorderService.lastFix;
        if (!rec) {
            detail.setText(R.string.idle_hint);
        } else if (!getSystemService(LocationManager.class).isLocationEnabled()) {
            detail.setText(R.string.location_off);
        } else if (fix == null) {
            detail.setText(getString(R.string.waiting_fix, RecorderService.savedPoints));
        } else {
            long ageS = (SystemClock.elapsedRealtimeNanos() - fix.getElapsedRealtimeNanos()) / 1_000_000_000L;
            detail.setText(getString(R.string.fix_status, RecorderService.savedPoints, ageS,
                    fix.getAccuracy(), fix.getLatitude(), fix.getLongitude()));
        }
        if (rec != wasRecording) {
            wasRecording = rec;
            reloadList();
        }
    }

    private void reloadList() {
        File[] files = TrackStore.list(this);
        adapter.clear();
        adapter.addAll(files);
        io.execute(() -> {
            for (File f : files) {
                if (f.equals(RecorderService.currentFile)) continue; // still growing
                SUMMARIES.computeIfAbsent(key(f), k -> summarize(TrackStore.read(f)));
            }
            main.post(adapter::notifyDataSetChanged);
        });
    }

    private static String key(File f) {
        return f.getName() + ':' + f.length();
    }

    private String summarize(List<TrackPoint> points) {
        if (points.isEmpty()) return getString(R.string.empty_track);
        long minutes = (points.get(points.size() - 1).timeMs - points.get(0).timeMs) / 60_000;
        return getString(R.string.summary, points.size(), Track.lengthM(points) / 1000, minutes / 60, minutes % 60);
    }

    private void openMap(File f) {
        startActivity(new Intent(this, MapActivity.class).putExtra(MapActivity.EXTRA_FILE, f.getName()));
    }

    private void showOptions(File f) {
        boolean active = f.equals(RecorderService.currentFile);
        CharSequence[] items = active
                ? new CharSequence[] {getString(R.string.export)}
                : new CharSequence[] {getString(R.string.export), getString(R.string.delete)};
        new AlertDialog.Builder(this)
                .setTitle(Track.title(f.getName()))
                .setItems(items, (d, which) -> {
                    if (which == 0) export(f);
                    else confirmDelete(f);
                })
                .show();
    }

    private void export(File f) {
        io.execute(() -> {
            int message;
            try {
                TrackStore.exportToDownloads(this, f);
                message = R.string.exported;
            } catch (IOException e) {
                message = R.string.export_failed;
            }
            int m = message;
            main.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        });
    }

    private void confirmDelete(File f) {
        new AlertDialog.Builder(this)
                .setTitle(Track.title(f.getName()))
                .setMessage(R.string.delete_confirm)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    if (!f.delete()) Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_SHORT).show();
                    reloadList();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
