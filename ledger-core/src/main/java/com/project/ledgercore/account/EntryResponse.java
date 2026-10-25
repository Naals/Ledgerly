package com.project.ledgercore.account;

import com.project.ledgercore.ledger.EntryView;
import java.time.OffsetDateTime;
import java.util.UUID;

public record EntryResponse(
        long id,
        long sequence,
        UUID transactionId,
        String type,
        String description,
        long amountMinor,
        long balanceAfterMinor,
        String currency,
        OffsetDateTime postedAt) {

    static EntryResponse from(EntryView entry) {
        return new EntryResponse(
                entry.id(),
                entry.sequence(),
                entry.transactionId(),
                entry.type().name(),
                entry.description(),
                entry.amount().minorUnits(),
                entry.balanceAfter().minorUnits(),
                entry.amount().currency().getCurrencyCode(),
                entry.postedAt());
    }
}