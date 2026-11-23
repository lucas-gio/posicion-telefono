package com.posiciontelefono;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** One GPS fix. accuracyM and speedKmh are NaN when the receiver did not report them. */
public final class TrackPoint {

    public static final String CSV_HEADER = "time,lat,lon,accuracy_m,speed_kmh,unix_ms";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    public final long timeMs;
    public final double lat, lon;
    public final float accuracyM, speedKmh;

    public TrackPoint(long timeMs, double lat, double lon, float accuracyM, float speedKmh) {
        this.timeMs = timeMs;
        this.lat = lat;
        this.lon = lon;
        this.accuracyM = accuracyM;
        this.speedKmh = speedKmh;
    }

    /** CSV row. Always uses '.' as decimal separator, whatever the phone's language is. */
    public String toCsv(ZoneId zone) {
        return TIME.format(Instant.ofEpochMilli(timeMs).atZone(zone))
                + String.format(Locale.ROOT, ",%.7f,%.7f,", lat, lon)
                + optional(accuracyM) + ',' + optional(speedKmh) + ',' + timeMs;
    }

    /** Parses a CSV row; returns null for the header or a malformed/truncated row. */
    public static TrackPoint fromCsv(String row) {
        String[] c = row.split(",", -1);
        if (c.length != 6) return null;
        try {
            double lat = Double.parseDouble(c[1]);
            double lon = Double.parseDouble(c[2]);
            if (!(Math.abs(lat) <= 90 && Math.abs(lon) <= 180)) return null; // also rejects NaN/Infinity
            return new TrackPoint(Long.parseLong(c[5]), lat, lon, parseOptional(c[3]), parseOptional(c[4]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public int quality() {
        return quality(accuracyM);
    }

    /** 0 = good (up to 10 m), 1 = fair (up to 25 m), 2 = poor or unknown. */
    public static int quality(float accuracyM) {
        return accuracyM <= 10 ? 0 : accuracyM <= 25 ? 1 : 2; // NaN compares false: poor
    }

    private static String optional(float v) {
        return Float.isNaN(v) ? "" : String.format(Locale.ROOT, "%.1f", v);
    }

    private static float parseOptional(String s) {
        if (s.isEmpty()) return Float.NaN;
        float v = Float.parseFloat(s);
        return Float.isFinite(v) && v >= 0 ? v : Float.NaN;
    }
}
