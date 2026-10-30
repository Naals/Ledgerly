package com.project.paymentservice.payment;

/** A payment, and whether it already existed (the same idempotency key was used before). */
public record CreatedPayment(Payment payment, boolean replayed) {
}