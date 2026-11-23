package com.posiciontelefono;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FormatTest {

    @Test
    public void clockForTheLiveTimer() {
        assertEquals("0:00:00", Format.clock(0));
        assertEquals("0:07:05", Format.clock(425_999));
        assertEquals("12:00:00", Format.clock(12 * 3_600_000L));
        assertEquals("0:00:00", Format.clock(-5)); // clock skew never shows a negative time
    }

    @Test
    public void rideDuration() {
        assertEquals("40 s", Format.duration(40_000));
        assertEquals("1 min", Format.duration(60_000));
        assertEquals("23 min", Format.duration(23 * 60_000 + 59_000));
        assertEquals("1 h 05 min", Format.duration(65 * 60_000));
    }

    @Test
    public void distanceUsesSpanishDecimalComma() {
        assertEquals("0 m", Format.distance(0));
        assertEquals("850 m", Format.distance(849.6));
        assertEquals("1,0 km", Format.distance(1000));
        assertEquals("3,4 km", Format.distance(3_420));
    }

    @Test
    public void trackTitleIsFriendly() {
        assertEquals("Domingo 22/11/2026 · 14:05", Format.trackTitle("2026-11-22_14-05-30.csv"));
        assertEquals("Lunes 5/1/2026 · 07:00", Format.trackTitle("2026-01-05_07-00-00.csv"));
    }

    @Test
    public void trackTitleFallsBackOnUnexpectedNames() {
        assertEquals("notes.txt", Format.trackTitle("notes.txt"));
        assertEquals("2026-13-45_99-00-00.csv", Format.trackTitle("2026-13-45_99-00-00.csv")); // passes the regex, not a date
    }
}
