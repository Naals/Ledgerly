package com.project.ledgercore.outbox;

/** How long to wait before delivering an event again after a failed attempt. */
public final class Backoff {

    static final long MAX_DELAY_SECONDS = 300;

    private Backoff() {
    }

    /** 1 s after the first failure, then 2, 4, 8 ... seconds, never more than five minutes. */
    public static long delaySecondsAfter(int failedAttempts) {
        int exponent = Math.min(Math.max(failedAttempts, 1) - 1, 20);
        return Math.min(MAX_DELAY_SECONDS, 1L << exponent);
    }
}