package com.posiciontelefono;

/** Running track length that skips inaccurate fixes, which zig-zag and would inflate the distance. */
public final class Odometer {

    static final float MAX_ACCURACY_M = 30;

    private double meters;
    private TrackPoint last;

    public void add(TrackPoint p) {
        if (p.accuracyM > MAX_ACCURACY_M) return;
        if (last != null) meters += Track.distanceM(last.lat, last.lon, p.lat, p.lon);
        last = p;
    }

    public double meters() {
        return meters;
    }
}
