package com.project.ledgercore.ledger;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        String type,
        String description,
        OffsetDateTime createdAt,
        UUID reversesTransactionId,
        UUID reversedByTransactionId,
        List<Line> entries) {

    public record Line(long id, UUID accountId, long amountMinor, String currency) {
    }

    static TransactionResponse from(TransactionView transaction) {
        return new TransactionResponse(
                transaction.id(),
                transaction.type().name(),
                transaction.description(),
                transaction.createdAt(),
                transaction.reversesTransactionId(),
                transaction.reversedByTransactionId(),
                transaction.entries().stream()
                        .map(entry -> new Line(
                                entry.id(),
                                entry.accountId(),
                                entry.amount().minorUnits(),
                                entry.amount().currency().getCurrencyCode()))
                        .toList());
    }
}