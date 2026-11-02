package com.project.ledgercore.ledger;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** A ledger transaction with all of its entries, its reversal links and the caller's reference. */
public record TransactionView(
        UUID id,
        TransactionType type,
        String description,
        OffsetDateTime createdAt,
        List<TransactionEntryView> entries,
        UUID reversesTransactionId,
        UUID reversedByTransactionId,
        String reference) {
}