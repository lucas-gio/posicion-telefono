package com.posiciontelefono;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SamplerTest {

    private static final long S = 1_000_000_000L;

    @Test
    public void firstFixIsAlwaysAccepted() {
        assertTrue(new Sampler(10 * S).accept(123));
    }

    @Test
    public void keepsOneFixPerInterval() {
        Sampler s = new Sampler(9_500_000_000L); // 10 s minus half of the 1 s GPS period
        int saved = 0;
        for (long t = 0; t <= 60; t++) { // one fix per second for a minute
            if (s.accept(t * S)) saved++;
        }
        assertEquals(7, saved); // t = 0, 10, 20, ..., 60
    }

    @Test
    public void toleratesJitterAroundTheInterval() {
        Sampler s = new Sampler(9_500_000_000L);
        assertTrue(s.accept(0));
        assertFalse(s.accept(9_400_000_000L));
        assertTrue(s.accept(9_990_000_000L)); // a fix arriving 10 ms early still counts
    }

    @Test
    public void staleFixesAreRejected() {
        Sampler s = new Sampler(10 * S);
        assertTrue(s.accept(100 * S));
        assertFalse(s.accept(50 * S));
    }

    @Test
    public void resumesAfterLosingSignal() {
        Sampler s = new Sampler(10 * S);
        assertTrue(s.accept(0));
        assertTrue(s.accept(300 * S)); // first fix after a tunnel is saved immediately
        assertFalse(s.accept(301 * S));
    }
}
