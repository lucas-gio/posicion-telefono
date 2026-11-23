package com.posiciontelefono;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Pure helpers for a recorded track: file naming, reading, distance and GPX export. */
public final class Track {

    private static final DateTimeFormatter FILE_NAME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss'.csv'", Locale.ROOT);
    private static final Pattern VALID_NAME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.csv");
    private static final double EARTH_RADIUS_M = 6_371_008.8;
    /** Fixes worse than this zig-zag around and would inflate the distance. */
    static final float MAX_ACCURACY_FOR_DISTANCE_M = 30;

    private Track() {}

    public static String fileName(long timeMs, ZoneId zone) {
        return FILE_NAME.format(Instant.ofEpochMilli(timeMs).atZone(zone));
    }

    /** Whitelist for names coming from outside (Intents, directory listings): blocks path traversal. */
    public static boolean isValidFileName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    /** "2026-11-22_14-05-30.csv" becomes "22/11/2026 14:05". */
    public static String title(String fileName) {
        if (!isValidFileName(fileName)) return fileName;
        return fileName.substring(8, 10) + '/' + fileName.substring(5, 7) + '/' + fileName.substring(0, 4)
                + ' ' + fileName.substring(11, 13) + ':' + fileName.substring(14, 16);
    }

    /** Reads all valid rows, silently skipping the header and damaged lines. */
    public static List<TrackPoint> read(BufferedReader in) throws IOException {
        List<TrackPoint> points = new ArrayList<>();
        for (String row; (row = in.readLine()) != null; ) {
            TrackPoint p = TrackPoint.fromCsv(row);
            if (p != null) points.add(p);
        }
        return points;
    }

    /** Great-circle distance (haversine). */
    public static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    public static double lengthM(List<TrackPoint> points) {
        double total = 0;
        TrackPoint prev = null;
        for (TrackPoint p : points) {
            if (p.accuracyM > MAX_ACCURACY_FOR_DISTANCE_M) continue;
            if (prev != null) total += distanceM(prev.lat, prev.lon, p.lat, p.lon);
            prev = p;
        }
        return total;
    }

    public static String toGpx(String name, List<TrackPoint> points) {
        StringBuilder sb = new StringBuilder(256 + points.size() * 96);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<gpx version=\"1.1\" creator=\"posicion-telefono\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
                .append("<trk><name>").append(escapeXml(name)).append("</name><trkseg>\n");
        for (TrackPoint p : points) {
            sb.append(String.format(Locale.ROOT, "<trkpt lat=\"%.7f\" lon=\"%.7f\"><time>", p.lat, p.lon))
                    .append(Instant.ofEpochMilli(p.timeMs))
                    .append("</time></trkpt>\n");
        }
        return sb.append("</trkseg></trk>\n</gpx>\n").toString();
    }

    static String escapeXml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default: sb.append(ch);
            }
        }
        return sb.toString();
    }
}
