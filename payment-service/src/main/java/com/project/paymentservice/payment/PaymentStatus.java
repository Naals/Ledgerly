package com.project.paymentservice.payment;

public enum PaymentStatus {
    PENDING,
    COMPLETED,
    FAILED,
    REVERSED;

    /** The moves the database allows: PENDING to COMPLETED or FAILED, and COMPLETED to REVERSED. Nothing else. */
    public boolean canMoveTo(PaymentStatus target) {
        return switch (this) {
            case PENDING -> target == COMPLETED || target == FAILED;
            case COMPLETED -> target == REVERSED;
            case FAILED, REVERSED -> false;
        };
    }
}