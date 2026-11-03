package com.project.paymentservice.payment;

public enum PaymentStatus {
    /** Registered; the ledger has not answered yet. */
    PENDING,
    /** MANUAL only: the ledger holds the money. Nothing has moved yet. */
    AUTHORIZED,
    COMPLETED,
    FAILED,
    /** MANUAL only: the authorization was cancelled and the hold released. */
    CANCELLED,
    /** MANUAL only: nobody captured in time and the hold ran out. */
    EXPIRED,
    REVERSED;

    /** Only a manual payment is ever authorized, cancelled or expired. */
    public boolean isPossibleFor(CaptureMode mode) {
        return switch (this) {
            case AUTHORIZED, CANCELLED, EXPIRED -> mode == CaptureMode.MANUAL;
            case PENDING, COMPLETED, FAILED, REVERSED -> true;
        };
    }

    /** The moves the database allows a payment of this mode to make (see V3). */
    public boolean canMoveTo(PaymentStatus target, CaptureMode mode) {
        return switch (this) {
            case PENDING -> switch (target) {
                case COMPLETED -> mode == CaptureMode.AUTOMATIC;
                case AUTHORIZED -> mode == CaptureMode.MANUAL;
                case FAILED -> true;
                default -> false;
            };
            case AUTHORIZED -> target == COMPLETED || target == CANCELLED || target == EXPIRED;
            case COMPLETED -> target == REVERSED;
            case FAILED, CANCELLED, EXPIRED, REVERSED -> false;
        };
    }

    /** True if a payment of some mode can make this move. */
    public boolean canMoveTo(PaymentStatus target) {
        return canMoveTo(target, CaptureMode.AUTOMATIC) || canMoveTo(target, CaptureMode.MANUAL);
    }
}