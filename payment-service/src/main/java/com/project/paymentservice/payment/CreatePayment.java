package com.project.paymentservice.payment;

import java.util.UUID;

/** A request to move money between two accounts. */
public record CreatePayment(UUID fromAccountId, UUID toAccountId, long amountMinor, String currency) {
}