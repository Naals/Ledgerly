package com.project.ledgercore.hold;

import java.time.OffsetDateTime;
import java.util.UUID;

public record HoldResponse(
        UUID id,
        UUID accountId,
        UUID toAccountId,
        long amountMinor,
        String currency,
        String status,
        String reference,
        OffsetDateTime expiresAt,
        Long capturedMinor,
        UUID ledgerTransactionId) {

    static HoldResponse from(Hold hold) {
        return new HoldResponse(
                hold.id(),
                hold.accountId(),
                hold.toAccountId(),
                hold.amount().minorUnits(),
                hold.amount().currency().getCurrencyCode(),
                hold.status().name(),
                hold.reference(),
                hold.expiresAt(),
                hold.captured() == null ? null : hold.captured().minorUnits(),
                hold.ledgerTransactionId());
    }
}