package com.project.paymentservice.ledger;

import com.project.paymentservice.payment.Payment;

/** The payment service's door to the ledger. */
public interface LedgerGateway {

    /**
     * Asks the ledger to execute the payment's transfer. The payment id is the idempotency key, so asking
     * again about the same payment can never move the money twice.
     */
    LedgerResult transfer(Payment payment);
}