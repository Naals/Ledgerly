package com.project.ledgercore.ledger;

import com.project.ledgercore.common.Money;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One ledger entry as seen on an account statement. */
public record EntryView(
        long id,
        UUID transactionId,
        TransactionType type,
        String description,
        Money amount,
        OffsetDateTime createdAt) {
}
