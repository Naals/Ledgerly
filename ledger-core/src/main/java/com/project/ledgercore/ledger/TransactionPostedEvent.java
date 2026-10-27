package com.project.ledgercore.ledger;

import java.util.List;
import java.util.UUID;

/**
 * The event published for every committed posting (outbox event type {@code TransactionPosted}).
 *
 * <p>This is a contract with other services: fields may be added, but none may be renamed or removed
 * without bumping {@link #VERSION}.
 */
public record TransactionPostedEvent(
        int version,
        UUID transactionId,
        String type,
        String description,
        UUID reversesTransactionId,
        List<Line> entries) {

    public static final int VERSION = 1;

    /** One entry of the posting: a signed amount, in minor units, on one account. */
    public record Line(UUID accountId, long amountMinor, String currency) {
    }

    static TransactionPostedEvent of(UUID transactionId, PostingRequest request) {
        return new TransactionPostedEvent(
                VERSION,
                transactionId,
                request.type().name(),
                request.description(),
                request.reversesTransactionId(),
                request.lines().stream()
                        .map(line -> new Line(
                                line.accountId(),
                                line.amount().minorUnits(),
                                line.amount().currency().getCurrencyCode()))
                        .toList());
    }
}