package com.posiciontelefono;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class TrackTest {

    @Test
    public void fileNameIsSortableLocalTime() {
        String name = Track.fileName(1_763_830_800_000L, ZoneId.of("America/Argentina/Buenos_Aires"));
        assertEquals("2025-11-22_14-00-00.csv", name);
        assertTrue(Track.isValidFileName(name));
    }

    @Test
    public void fileNameWhitelistBlocksPathTraversal() {
        assertFalse(Track.isValidFileName(null));
        assertFalse(Track.isValidFileName(""));
        assertFalse(Track.isValidFileName("../../shared_prefs/x.xml"));
        assertFalse(Track.isValidFileName("../2025-11-22_14-00-00.csv"));
        assertFalse(Track.isValidFileName("2025-11-22_14-00-00.csv/.."));
        assertFalse(Track.isValidFileName("2025-11-22_14-00-00.csv\n"));
        assertFalse(Track.isValidFileName("2025-11-22_14-00-00.gpx"));
    }

    @Test
    public void readSkipsHeaderAndDamagedRows() throws IOException {
        String csv = TrackPoint.CSV_HEADER + "\n"
                + "2025-11-22 14:00:00,-34.6000000,-58.4000000,4.0,10.0,1000\n"
                + "garbage\n"
                + "\n"
                + "2025-11-22 14:00:10,-34.6010000,-58.4000000,5.0,12.0,11000\n"
                + "2025-11-22 14:00:20,-34.60"; // last row cut mid-write
        List<TrackPoint> points = Track.read(new BufferedReader(new StringReader(csv)));
        assertEquals(2, points.size());
        assertEquals(11_000, points.get(1).timeMs);
    }

    @Test
    public void haversineMatchesKnownDistances() {
        // One degree of latitude is about 111.2 km.
        assertEquals(111_195, Track.distanceM(0, 0, 1, 0), 5);
        // Buenos Aires: Obelisco to Casa Rosada, about 1.14 km.
        assertEquals(1_144, Track.distanceM(-34.60373, -58.38157, -34.60807, -58.37024), 5);
        assertEquals(0, Track.distanceM(-34.6, -58.4, -34.6, -58.4), 1e-9);
    }

    @Test
    public void lengthIgnoresInaccurateFixes() {
        List<TrackPoint> points = Arrays.asList(
                point(0, 0.000, 5),
                point(1, 0.001, 200), // wild jump with a bad fix: ignored
                point(2, 0.002, 5));
        assertEquals(Track.distanceM(0, 0, 0.002, 0), Track.lengthM(points), 1e-6);
        assertEquals(0, Track.lengthM(Collections.emptyList()), 0);
    }

    @Test
    public void durationSpansFirstToLastFix() {
        assertEquals(0, Track.durationMs(Collections.emptyList()));
        assertEquals(0, Track.durationMs(Collections.singletonList(point(5_000, 0, 5))));
        assertEquals(20_000, Track.durationMs(Arrays.asList(point(1_000, 0, 5), point(21_000, 0, 5))));
    }

    @Test
    public void gpxHasPointsInUtcAndEscapesName() {
        String gpx = Track.toGpx("a<b>&\"c'", Collections.singletonList(point(1_763_830_800_000L, -34.5, 3)));
        assertTrue(gpx.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(gpx.contains("<name>a&lt;b&gt;&amp;&quot;c&apos;</name>"));
        assertTrue(gpx.contains("<trkpt lat=\"-34.5000000\" lon=\"-58.4000000\"><time>2025-11-22T17:00:00Z</time></trkpt>"));
        assertTrue(gpx.trim().endsWith("</gpx>"));
    }

    private static TrackPoint point(long timeMs, double lat, float accuracy) {
        return new TrackPoint(timeMs, lat, -58.4, accuracy, Float.NaN);
    }
}
