package com.project.paymentservice.payment;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A payment: a customer's request to move money between two accounts, executed through the ledger.
 *
 * @param ledgerHoldId             MANUAL payments only: the ledger hold that reserves the money (once authorized)
 * @param authorizationExpiresAt   when that hold runs out; set together with {@code ledgerHoldId}
 * @param ledgerTransactionId      the ledger transaction that moved the money (once completed)
 */
public record Payment(
        UUID id,
        String idempotencyKey,
        String requestHash,
        UUID fromAccountId,
        UUID toAccountId,
        long amountMinor,
        String currency,
        CaptureMode captureMode,
        PaymentStatus status,
        String failureCode,
        UUID ledgerTransactionId,
        UUID ledgerHoldId,
        OffsetDateTime authorizationExpiresAt,
        int attempts,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}