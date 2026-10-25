package com.project.ledgercore.ledger;

import com.project.ledgercore.common.Money;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One entry on a customer account's statement. */
public record EntryView(
        long id,
        long sequence,
        UUID transactionId,
        TransactionType type,
        String description,
        Money amount,
        Money balanceAfter,
        OffsetDateTime postedAt) {
}