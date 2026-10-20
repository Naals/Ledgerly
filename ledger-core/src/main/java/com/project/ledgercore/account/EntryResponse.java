package com.project.ledgercore.account;

import com.project.ledgercore.ledger.EntryView;
import java.time.OffsetDateTime;
import java.util.UUID;

public record EntryResponse(
        long id,
        UUID transactionId,
        String type,
        String description,
        long amountMinor,
        String currency,
        OffsetDateTime createdAt) {

    static EntryResponse from(EntryView entry) {
        return new EntryResponse(
                entry.id(),
                entry.transactionId(),
                entry.type().name(),
                entry.description(),
                entry.amount().minorUnits(),
                entry.amount().currency().getCurrencyCode(),
                entry.createdAt());
    }
}