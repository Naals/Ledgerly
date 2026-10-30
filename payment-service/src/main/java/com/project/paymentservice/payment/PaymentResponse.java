package com.project.paymentservice.payment;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        String status,
        String failureCode,
        UUID ledgerTransactionId,
        UUID fromAccountId,
        UUID toAccountId,
        long amountMinor,
        String currency,
        OffsetDateTime createdAt) {

    static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.id(),
                payment.status().name(),
                payment.failureCode(),
                payment.ledgerTransactionId(),
                payment.fromAccountId(),
                payment.toAccountId(),
                payment.amountMinor(),
                payment.currency(),
                payment.createdAt());
    }
}