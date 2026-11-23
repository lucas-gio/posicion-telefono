package com.posiciontelefono;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.ZoneId;
import java.util.Locale;

import org.junit.After;
import org.junit.Test;

public class TrackPointTest {

    private static final ZoneId BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");
    private final Locale original = Locale.getDefault();

    @After
    public void restoreLocale() {
        Locale.setDefault(original);
    }

    @Test
    public void csvUsesLocalTimeAndDotDecimalsEvenInSpanish() {
        Locale.setDefault(Locale.forLanguageTag("es-AR")); // decimal separator is ',' here
        TrackPoint p = new TrackPoint(1_763_830_800_000L, -34.6037345, -58.3815704, 4.2f, 18.5f);
        assertEquals("2025-11-22 14:00:00,-34.6037345,-58.3815704,4.2,18.5,1763830800000", p.toCsv(BUENOS_AIRES));
    }

    @Test
    public void roundTrip() {
        TrackPoint p = new TrackPoint(1_763_830_800_123L, 10.5, -20.25, 3.5f, 0f);
        TrackPoint back = TrackPoint.fromCsv(p.toCsv(BUENOS_AIRES));
        assertNotNull(back);
        assertEquals(p.timeMs, back.timeMs);
        assertEquals(p.lat, back.lat, 1e-9);
        assertEquals(p.lon, back.lon, 1e-9);
        assertEquals(p.accuracyM, back.accuracyM, 1e-6);
        assertEquals(p.speedKmh, back.speedKmh, 1e-6);
    }

    @Test
    public void missingOptionalValuesStayEmptyAndParseAsNaN() {
        TrackPoint p = new TrackPoint(0, 1, 2, Float.NaN, Float.NaN);
        String row = p.toCsv(ZoneId.of("UTC"));
        assertEquals("1970-01-01 00:00:00,1.0000000,2.0000000,,,0", row);
        TrackPoint back = TrackPoint.fromCsv(row);
        assertTrue(Float.isNaN(back.accuracyM));
        assertTrue(Float.isNaN(back.speedKmh));
    }

    @Test
    public void rejectsHeaderAndMalformedRows() {
        assertNull(TrackPoint.fromCsv(TrackPoint.CSV_HEADER));
        assertNull(TrackPoint.fromCsv(""));
        assertNull(TrackPoint.fromCsv("2025-11-22 14:00:00,-34.6,-58.3")); // truncated by a power loss
        assertNull(TrackPoint.fromCsv("t,-34.6,-58.3,4,10,1,extra"));
        assertNull(TrackPoint.fromCsv("t,abc,-58.3,4,10,1"));
        assertNull(TrackPoint.fromCsv("t,-34.6,-58.3,4,10,notanumber"));
    }

    @Test
    public void rejectsImpossibleCoordinates() {
        assertNull(TrackPoint.fromCsv("t,91,0,,,1"));
        assertNull(TrackPoint.fromCsv("t,0,180.5,,,1"));
        assertNull(TrackPoint.fromCsv("t,NaN,0,,,1"));
        assertNull(TrackPoint.fromCsv("t,0,Infinity,,,1"));
    }

    @Test
    public void negativeOrInfiniteOptionalsBecomeUnknown() {
        TrackPoint p = TrackPoint.fromCsv("t,0,0,-5,Infinity,1");
        assertTrue(Float.isNaN(p.accuracyM));
        assertTrue(Float.isNaN(p.speedKmh));
    }

    @Test
    public void qualityBuckets() {
        assertEquals(0, at(3).quality());
        assertEquals(0, at(10).quality());
        assertEquals(1, at(10.1f).quality());
        assertEquals(1, at(25).quality());
        assertEquals(2, at(40).quality());
        assertEquals(2, at(Float.NaN).quality());
    }

    private static TrackPoint at(float accuracy) {
        return new TrackPoint(0, 0, 0, accuracy, Float.NaN);
    }
}
