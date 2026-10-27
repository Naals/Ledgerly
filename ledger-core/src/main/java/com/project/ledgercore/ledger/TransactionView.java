package com.project.ledgercore.ledger;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** A ledger transaction with all of its entries and its reversal links. */
public record TransactionView(
        UUID id,
        TransactionType type,
        String description,
        OffsetDateTime createdAt,
        List<TransactionEntryView> entries,
        UUID reversesTransactionId,
        UUID reversedByTransactionId) {
}