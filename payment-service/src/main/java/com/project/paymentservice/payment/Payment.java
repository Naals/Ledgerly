package com.project.paymentservice.payment;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Payment(
        UUID id,
        String idempotencyKey,
        String requestHash,
        UUID fromAccountId,
        UUID toAccountId,
        long amountMinor,
        String currency,
        PaymentStatus status,
        String failureCode,
        UUID ledgerTransactionId,
        int attempts,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}