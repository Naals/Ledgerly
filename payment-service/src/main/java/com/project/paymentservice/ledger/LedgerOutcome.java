package com.project.paymentservice.ledger;

/**
 * What came back from a ledger call that returns a value: the value, a final refusal, or no answer.
 * ({@link LedgerResult} does the same job for the one-step transfer.)
 */
public sealed interface LedgerOutcome<T> {

    /** The ledger did it (or had already done it: replays count) and answered with this. */
    record Done<T>(T value) implements LedgerOutcome<T> {
    }

    /** The ledger refused for good. The code says why, for example {@code hold-expired}. */
    record Rejected<T>(String code) implements LedgerOutcome<T> {
    }

    /** The ledger could not be reached or could not answer: the outcome is unknown and the call may be repeated. */
    record Unavailable<T>(String reason) implements LedgerOutcome<T> {
    }
}