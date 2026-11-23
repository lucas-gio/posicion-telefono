package com.posiciontelefono;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads OpenStreetMap tiles in the background and keeps them in a memory LRU.
 * Disk caching is done by HttpResponseCache (installed by MapActivity), honoring the server's headers.
 * All public methods must be called on the main thread.
 */
final class TileCache {

    static final int TILE_PX = 256;
    private static final String URL_FORMAT = "https://tile.openstreetmap.org/%d/%d/%d.png";
    private static final String USER_AGENT = "PosicionTelefono/1.0 (Android)"; // required by the OSM tile policy
    private static final int MAX_TILE_BYTES = 256 * 1024;
    private static final long RETRY_AFTER_MS = 15_000;

    private final Runnable onLoaded;
    private final Handler main = new Handler(Looper.getMainLooper());
    // OSM asks apps to keep concurrent connections low.
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final LruCache<Long, Bitmap> memory;
    private final LruCache<Long, Long> failedAt = new LruCache<>(128);
    private final Set<Long> pending = new HashSet<>();

    // Tiles currently on screen, so queued downloads that scrolled out of view are skipped.
    private volatile int wantZ, wantX0, wantX1, wantY0, wantY1;

    TileCache(Runnable onLoaded) {
        this.onLoaded = onLoaded;
        int bytes = (int) Math.min(Runtime.getRuntime().maxMemory() / 8, 48L << 20);
        memory = new LruCache<Long, Bitmap>(bytes) {
            @Override
            protected int sizeOf(Long key, Bitmap b) {
                return b.getByteCount();
            }
        };
    }

    void setVisible(int z, int x0, int x1, int y0, int y1) {
        wantZ = z;
        wantX0 = x0;
        wantX1 = x1;
        wantY0 = y0;
        wantY1 = y1;
    }

    /** Cached tile or null, without triggering a download. */
    Bitmap peek(int z, int x, int y) {
        return memory.get(Mercator.tileKey(z, x, y));
    }

    /** Cached tile, or null after scheduling its download. */
    Bitmap get(int z, int x, int y) {
        long key = Mercator.tileKey(z, x, y);
        Bitmap b = memory.get(key);
        if (b != null || pending.contains(key)) return b;
        Long failed = failedAt.get(key);
        if (failed != null && SystemClock.uptimeMillis() - failed < RETRY_AFTER_MS) return null;
        pending.add(key);
        pool.execute(() -> {
            boolean wanted = isWanted(z, x, y);
            Bitmap bmp = wanted ? download(z, x, y) : null;
            main.post(() -> {
                pending.remove(key);
                if (bmp != null) {
                    memory.put(key, bmp);
                    onLoaded.run();
                } else if (wanted) {
                    failedAt.put(key, SystemClock.uptimeMillis());
                }
            });
        });
        return null;
    }

    void shutdown() {
        pool.shutdownNow();
        main.removeCallbacksAndMessages(null);
        memory.evictAll();
    }

    private boolean isWanted(int z, int x, int y) {
        int n = 1 << z;
        return z == wantZ && y >= wantY0 && y <= wantY1
                && Math.floorMod(x - wantX0, n) <= wantX1 - wantX0;
    }

    private static Bitmap download(int z, int x, int y) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(String.format(Locale.ROOT, URL_FORMAT, z, x, y)).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            c.setRequestProperty("User-Agent", USER_AGENT);
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            byte[] data;
            try (InputStream in = c.getInputStream()) {
                data = readCapped(in, MAX_TILE_BYTES);
            }
            if (data == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565; // tiles are opaque: half the memory of ARGB
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            return b != null && b.getWidth() == TILE_PX && b.getHeight() == TILE_PX ? b : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Reads the whole stream, or returns null if it is larger than max (protects memory). */
    static byte[] readCapped(InputStream in, int max) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(32 * 1024);
        byte[] chunk = new byte[8192];
        for (int n; (n = in.read(chunk)) != -1; ) {
            if (buf.size() + n > max) return null;
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }
}
