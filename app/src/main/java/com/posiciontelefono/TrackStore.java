package com.posiciontelefono;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Tracks live in the app's private storage, so no other app can read them. */
final class TrackStore {

    private TrackStore() {}

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "tracks");
        if (!d.isDirectory() && !d.mkdirs()) throw new IllegalStateException("Cannot create " + d);
        return d;
    }

    static File newFile(Context c, long timeMs) {
        return new File(dir(c), Track.fileName(timeMs, ZoneId.systemDefault()));
    }

    /** Resolves a name received in an Intent; null unless it is an existing track file. */
    static File find(Context c, String name) {
        if (!Track.isValidFileName(name)) return null;
        File f = new File(dir(c), name);
        return f.isFile() ? f : null;
    }

    /** Newest first. */
    static File[] list(Context c) {
        File[] files = dir(c).listFiles((d, n) -> Track.isValidFileName(n));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName).reversed());
        return files;
    }

    static List<TrackPoint> read(File f) {
        try (BufferedReader in = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            return Track.read(in);
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    /** Copies the track as CSV and GPX to Downloads/PosicionTelefono (survives uninstalling the app). */
    static void exportToDownloads(Context c, File f) throws IOException {
        String base = f.getName().substring(0, f.getName().length() - 4);
        write(c, base + ".csv", "text/csv", Files.readAllBytes(f.toPath()));
        write(c, base + ".gpx", "application/gpx+xml",
                Track.toGpx(base, read(f)).getBytes(StandardCharsets.UTF_8));
    }

    private static void write(Context c, String name, String mime, byte[] data) throws IOException {
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PosicionTelefono");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("MediaStore insert failed: " + name);
        try (OutputStream out = cr.openOutputStream(uri)) {
            if (out == null) throw new IOException("Cannot open " + name);
            out.write(data);
        } catch (IOException e) {
            cr.delete(uri, null, null);
            throw e;
        }
        v.clear();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, v, null, null);
    }
}
