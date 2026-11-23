package com.posiciontelefono;

/** Web Mercator math in normalized world coordinates: both axes go from 0 to 1. */
public final class Mercator {

    public static final int MIN_ZOOM = 2;
    public static final int MAX_ZOOM = 19; // highest zoom served by tile.openstreetmap.org
    static final int MAX_FIT_ZOOM = 17;
    private static final double MAX_LAT = 85.05112878;

    private Mercator() {}

    public static double x(double lon) {
        return (lon + 180) / 360;
    }

    public static double y(double lat) {
        double s = Math.sin(Math.toRadians(Math.max(-MAX_LAT, Math.min(MAX_LAT, lat))));
        return 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI);
    }

    /** Zoom that fits a dx by dy span (normalized units) into a w by h pixel view. */
    public static double fitZoom(double dx, double dy, int w, int h, double tilePx) {
        double scale = Math.min(w / Math.max(dx, 1e-12), h / Math.max(dy, 1e-12)) / tilePx;
        return clamp(Math.log(scale) / Math.log(2), MIN_ZOOM, MAX_FIT_ZOOM);
    }

    /** Packs a tile address into one long for cache keys (x and y need at most 19 bits). */
    public static long tileKey(int z, int x, int y) {
        return ((long) z << 50) | ((long) x << 25) | y;
    }

    static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
