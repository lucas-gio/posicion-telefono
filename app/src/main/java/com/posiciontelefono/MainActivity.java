package com.posiciontelefono;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
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
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {

    /** Set by the Quick Settings tile: start recording as soon as the app opens. */
    static final String EXTRA_START = "start";
    private static final int PERMISSIONS_REQUEST = 1;
    private static final String PREF_TILE_ADDED = "tile_added";
    private static final int[] QUALITY_COLORS = {R.color.quality_good, R.color.quality_fair, R.color.quality_poor};
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

    private TextView state, timer, stats, gps, hint, empty;
    private Button toggle;
    private View addTile, rowPermission, rowLocation, rowNotifications, rowBattery;
    private ArrayAdapter<File> adapter;
    private Boolean rendered; // recording state currently on screen; null before the first render
    private boolean startWhenAllowed;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_main);
        ListView list = findViewById(R.id.list);
        View header = getLayoutInflater().inflate(R.layout.main_header, list, false);
        list.addHeaderView(header, null, false);

        state = header.findViewById(R.id.state);
        timer = header.findViewById(R.id.timer);
        stats = header.findViewById(R.id.stats);
        gps = header.findViewById(R.id.gps);
        hint = header.findViewById(R.id.hint);
        empty = header.findViewById(R.id.empty);
        toggle = header.findViewById(R.id.toggle);
        toggle.setOnClickListener(v -> onToggle());

        rowPermission = problem(header, R.id.row_permission, R.string.problem_permission, R.string.fix_permission,
                this::askLocationPermission);
        rowLocation = problem(header, R.id.row_location, R.string.problem_location_off, R.string.fix_turn_on,
                () -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        rowNotifications = problem(header, R.id.row_notifications, R.string.problem_notifications, R.string.fix_turn_on,
                () -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));
        rowBattery = problem(header, R.id.row_battery, R.string.problem_battery, R.string.fix_allow,
                this::askUnrestrictedBattery);

        addTile = header.findViewById(R.id.add_tile);
        if (Build.VERSION.SDK_INT >= 33 && !prefs().getBoolean(PREF_TILE_ADDED, false)) {
            addTile.setVisibility(View.VISIBLE);
            addTile.setOnClickListener(v -> askToAddTile());
        }

        adapter = new ArrayAdapter<File>(this, R.layout.item_track, R.id.title) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                File f = getItem(position);
                ((TextView) v.findViewById(R.id.title)).setText(Format.trackTitle(f.getName()));
                String summary = f.equals(RecorderService.currentFile)
                        ? getString(R.string.recording_now) : SUMMARIES.get(key(f));
                ((TextView) v.findViewById(R.id.summary)).setText(summary != null ? summary : "…");
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, v, position, id) -> {
            Object item = parent.getItemAtPosition(position); // accounts for the header
            if (item instanceof File) openMap((File) item);
        });
        if (saved == null) startIfAsked(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        startIfAsked(intent);
    }

    private void startIfAsked(Intent intent) {
        if (intent.getBooleanExtra(EXTRA_START, false) && !RecorderService.recording) onToggle();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkProblems();
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

    // ---------- start / finish ----------

    private void onToggle() {
        if (RecorderService.recording) {
            RecorderService.stop(this);
        } else if (!hasPreciseLocation()) {
            startWhenAllowed = true;
            askLocationPermission();
            return;
        } else if (!locationEnabled()) {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        } else {
            RecorderService.start(this);
        }
        main.postDelayed(this::render, 300);
    }

    private void askLocationPermission() {
        requestPermissions(Build.VERSION.SDK_INT >= 33
                ? new String[] {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.POST_NOTIFICATIONS}
                : new String[] {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                PERMISSIONS_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != PERMISSIONS_REQUEST) return;
        boolean start = startWhenAllowed;
        startWhenAllowed = false;
        checkProblems();
        if (hasPreciseLocation()) {
            if (start) onToggle();
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            // Android will not ask again: only the settings screen can grant it now.
            new AlertDialog.Builder(this)
                    .setMessage(R.string.need_precise)
                    .setPositiveButton(R.string.open_settings, (d, w) -> startActivity(new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }
    }

    @SuppressLint("BatteryLife") // a GPS logger is an accepted use case, and the APK is sideloaded
    private void askUnrestrictedBattery() {
        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName())));
    }

    @TargetApi(33)
    private void askToAddTile() {
        getSystemService(StatusBarManager.class).requestAddTileService(
                new ComponentName(this, RecordTileService.class), getString(R.string.tile_label),
                Icon.createWithResource(this, R.drawable.ic_notification), getMainExecutor(), result -> {
                    if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                            || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                        prefs().edit().putBoolean(PREF_TILE_ADDED, true).apply();
                        addTile.setVisibility(View.GONE);
                    }
                });
    }

    // ---------- state ----------

    private boolean hasPreciseLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean locationEnabled() {
        return getSystemService(LocationManager.class).isLocationEnabled();
    }

    /** Settings that only change outside the app, so checking them on resume is enough. */
    private void checkProblems() {
        show(rowPermission, !hasPreciseLocation());
        show(rowNotifications, !getSystemService(NotificationManager.class).areNotificationsEnabled());
        show(rowBattery, !getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName()));
    }

    /** Runs every second, so it only touches views whose content actually changed. */
    private void render() {
        boolean rec = RecorderService.recording;
        show(rowLocation, !locationEnabled()); // can be toggled from the notification shade
        if (rendered == null || rendered != rec) {
            if (rendered != null) reloadList(); // a recording started or finished (onResume loads it the first time)
            rendered = rec;
            state.setText(rec ? R.string.state_recording : R.string.state_idle);
            state.setTextColor(getColor(rec ? R.color.stop : R.color.start));
            show(timer, rec);
            show(stats, rec);
            show(gps, rec);
            hint.setText(rec ? R.string.hint_recording : R.string.hint_idle);
            toggle.setText(rec ? R.string.finish_button : R.string.start);
            toggle.setBackgroundTintList(getColorStateList(rec ? R.color.stop : R.color.start));
        }
        if (!rec) return;
        setText(timer, Format.clock(SystemClock.elapsedRealtime() - RecorderService.startedElapsedMs));
        setText(stats, getString(R.string.live_stats,
                Format.distance(RecorderService.distanceM), RecorderService.savedPoints));
        Location fix = RecorderService.lastFix;
        if (setText(gps, RecorderService.gpsStatus(this, fix))) {
            gps.setTextColor(getColor(QUALITY_COLORS[fix == null ? 2 : TrackPoint.quality(fix.getAccuracy())]));
        }
    }

    // ---------- track list ----------

    private void reloadList() {
        File[] files = TrackStore.list(this);
        adapter.clear();
        adapter.addAll(files);
        show(empty, files.length == 0);
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
        return getString(R.string.track_summary,
                Format.distance(Track.lengthM(points)), Format.duration(Track.durationMs(points)));
    }

    private void openMap(File f) {
        startActivity(new Intent(this, MapActivity.class).putExtra(MapActivity.EXTRA_FILE, f.getName()));
    }

    // ---------- helpers ----------

    private View problem(View header, int rowId, int text, int fixText, Runnable fix) {
        View row = header.findViewById(rowId);
        ((TextView) row.findViewById(R.id.problem_text)).setText(text);
        Button button = row.findViewById(R.id.problem_fix);
        button.setText(fixText);
        button.setOnClickListener(v -> fix.run());
        return row;
    }

    private static void show(View v, boolean visible) {
        v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** Avoids a relayout when the text did not change; returns whether it changed. */
    private static boolean setText(TextView v, CharSequence text) {
        if (TextUtils.equals(v.getText(), text)) return false;
        v.setText(text);
        return true;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("ui", MODE_PRIVATE);
    }
}
