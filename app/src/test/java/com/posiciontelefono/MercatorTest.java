package com.posiciontelefono;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.Test;

public class MercatorTest {

    @Test
    public void projectsKnownPoints() {
        assertEquals(0.5, Mercator.x(0), 1e-12);
        assertEquals(0.0, Mercator.x(-180), 1e-12);
        assertEquals(1.0, Mercator.x(180), 1e-12);
        assertEquals(0.5, Mercator.y(0), 1e-12);
        assertEquals(0.0, Mercator.y(85.05112878), 1e-9);
        assertEquals(1.0, Mercator.y(-85.05112878), 1e-9);
    }

    @Test
    public void polesAreClampedInsteadOfInfinite() {
        assertEquals(0.0, Mercator.y(90), 1e-9);
        assertEquals(1.0, Mercator.y(-90), 1e-9);
    }

    @Test
    public void matchesOsmTileNumbering() {
        // Reference values from the OSM wiki formula: y = (1 - asinh(tan(lat)) / pi) / 2 * 2^z.
        int n = 1 << 16;
        assertEquals(22139, (int) Math.floor(Mercator.x(-58.38157) * n));
        assertEquals(39489, (int) Math.floor(Mercator.y(-34.60373) * n));
    }

    @Test
    public void fitZoomShowsTheWholeSpan() {
        double tilePx = 256 * 2.625; // Moto G52 density
        double span = 0.0005; // about 20 km
        double z = Mercator.fitZoom(span, span, 1080, 2000, tilePx);
        assertTrue(span * tilePx * Math.pow(2, z) <= 1080 + 1e-6);
        assertEquals(Mercator.MAX_FIT_ZOOM, Mercator.fitZoom(0, 0, 1080, 2000, tilePx), 0); // single point
        assertEquals(Mercator.MIN_ZOOM, Mercator.fitZoom(1, 1, 100, 100, tilePx), 0);
    }

    @Test
    public void tileKeysAreUnique() {
        int max = (1 << Mercator.MAX_ZOOM) - 1;
        assertNotEquals(Mercator.tileKey(19, max, 0), Mercator.tileKey(19, 0, max));
        assertNotEquals(Mercator.tileKey(18, 1, 2), Mercator.tileKey(19, 1, 2));
    }

    @Test
    public void tileDownloadsAreSizeCapped() throws IOException {
        byte[] small = new byte[1000];
        assertArrayEquals(small, TileCache.readCapped(new ByteArrayInputStream(small), 1000));
        assertNull(TileCache.readCapped(new ByteArrayInputStream(new byte[1001]), 1000));
    }
}
