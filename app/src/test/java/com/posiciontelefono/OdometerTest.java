package com.posiciontelefono;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class OdometerTest {

    @Test
    public void startsAtZeroAndAddsSegments() {
        Odometer o = new Odometer();
        assertEquals(0, o.meters(), 0);
        o.add(at(0.000, 5));
        assertEquals(0, o.meters(), 0); // a single fix is not a distance
        o.add(at(0.001, 5));
        o.add(at(0.002, 5));
        assertEquals(Track.distanceM(0, 0, 0.002, 0), o.meters(), 1e-6);
    }

    @Test
    public void skipsInaccurateFixesButKeepsUnknownAccuracy() {
        Odometer o = new Odometer();
        o.add(at(0.000, 5));
        o.add(at(0.010, 31)); // > 30 m: would add a fake 1 km zig-zag
        o.add(at(0.001, Float.NaN)); // no accuracy reported: still counted
        assertEquals(Track.distanceM(0, 0, 0.001, 0), o.meters(), 1e-6);
    }

    @Test
    public void matchesTrackLengthForAWholeList() {
        Odometer o = new Odometer();
        java.util.List<TrackPoint> pts = java.util.Arrays.asList(at(0, 5), at(0.003, 50), at(0.004, 8));
        for (TrackPoint p : pts) o.add(p);
        assertEquals(Track.lengthM(pts), o.meters(), 0);
    }

    private static TrackPoint at(double lat, float accuracy) {
        return new TrackPoint(0, lat, 0, accuracy, Float.NaN);
    }
}
