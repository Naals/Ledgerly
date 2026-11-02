package com.project.paymentservice.events;

import com.project.paymentservice.payment.PaymentStatus;

/** What to do with a reversal event, given the state of the payment it is about. */
enum ReversalAction {
    /** The payment is COMPLETED: make it REVERSED. */
    REVERSE,
    /** Already REVERSED (by an earlier event): nothing to do. */
    ALREADY_REVERSED,
    /**
     * The payment is still PENDING: the ledger has already moved the money, but this service has not yet recorded
     * the outcome. Do not decide anything yet; fail so the event is delivered again later.
     */
    WAIT_FOR_PAYMENT,
    /** The payment FAILED, so no money moved for it and there is nothing to reverse. */
    NOT_REVERSIBLE;

    static ReversalAction forPayment(PaymentStatus status) {
        return switch (status) {
            case COMPLETED -> REVERSE;
            case REVERSED -> ALREADY_REVERSED;
            case PENDING -> WAIT_FOR_PAYMENT;
            case FAILED -> NOT_REVERSIBLE;
        };
    }
}