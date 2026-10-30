package com.project.paymentservice.ledger;

import java.util.UUID;

/** What came back from asking the ledger to execute a payment. */
public sealed interface LedgerResult {

    /** The ledger posted the transfer. */
    record Completed(UUID transactionId) implements LedgerResult {
    }

    /** The ledger refused the transfer for good. The code says why, for example {@code insufficient-funds}. */
    record Rejected(String code) implements LedgerResult {
    }

    /** The ledger could not be reached or could not answer: the outcome is unknown and the call may be repeated. */
    record Unavailable(String reason) implements LedgerResult {
    }
}