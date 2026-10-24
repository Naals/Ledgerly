package com.project.ledgercore.support;

import java.util.Arrays;
import java.util.Collection;

/** Latency statistics over a set of measurements: mean, percentiles (nearest rank) and maximum. */
public final class LatencyStats {

    private final long[] sortedNanos;

    private LatencyStats(long[] sortedNanos) {
        this.sortedNanos = sortedNanos;
    }

    public static LatencyStats of(Collection<Long> nanos) {
        if (nanos.isEmpty()) {
            throw new IllegalArgumentException("no measurements");
        }
        return new LatencyStats(nanos.stream().mapToLong(Long::longValue).sorted().toArray());
    }

    public int count() {
        return sortedNanos.length;
    }

    public double meanMillis() {
        return Arrays.stream(sortedNanos).average().orElseThrow() / 1_000_000.0;
    }

    /** Nearest-rank percentile: the smallest measurement that is at least as large as p percent of them. */
    public double percentileMillis(double p) {
        if (p <= 0 || p > 100) {
            throw new IllegalArgumentException("p must be in (0, 100], was " + p);
        }
        int rank = (int) Math.ceil(p * sortedNanos.length / 100.0);
        return sortedNanos[rank - 1] / 1_000_000.0;
    }

    public double maxMillis() {
        return sortedNanos[sortedNanos.length - 1] / 1_000_000.0;
    }
}