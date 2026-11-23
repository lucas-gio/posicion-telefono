package com.posiciontelefono;

/** Lets through at most one fix per interval, based on the fix's own monotonic timestamp. */
public final class Sampler {

    private final long minGapNs;
    private long lastNs;
    private boolean started;

    public Sampler(long minGapNs) {
        this.minGapNs = minGapNs;
    }

    public boolean accept(long elapsedNs) {
        if (started && elapsedNs - lastNs < minGapNs) return false;
        started = true;
        lastNs = elapsedNs;
        return true;
    }
}
