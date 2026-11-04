package com.project.paymentservice.events;

import com.project.paymentservice.payment.PaymentStatus;

/** What to do with a transfer event that carries a manual payment's id, given the state of that payment. */
enum CaptureEventAction {
    /** The payment is AUTHORIZED and the ledger has captured it: make it COMPLETED. */
    COMPLETE,
    /** Already COMPLETED (or even REVERSED): this service already knew. */
    ALREADY_COMPLETED,
    /** The payment is still PENDING: the authorization has not been recorded yet. Fail so the event comes again. */
    WAIT_FOR_AUTHORIZATION,
    /** The ledger moved money for a payment that FAILED, was CANCELLED or EXPIRED. The two services disagree: raise it. */
    INCONSISTENT;

    static CaptureEventAction forPayment(PaymentStatus status) {
        return switch (status) {
            case AUTHORIZED -> COMPLETE;
            case COMPLETED, REVERSED -> ALREADY_COMPLETED;
            case PENDING -> WAIT_FOR_AUTHORIZATION;
            case FAILED, CANCELLED, EXPIRED -> INCONSISTENT;
        };
    }
}