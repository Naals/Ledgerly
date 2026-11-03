package com.project.paymentservice.ledger;

import com.project.paymentservice.payment.Payment;
import java.util.UUID;

/** The payment service's door to the ledger. The payment id is the idempotency key of every call that changes something. */
public interface LedgerGateway {

    /** One-step payment: asks the ledger to execute the payment's transfer. Asking again can never move the money twice. */
    LedgerResult transfer(Payment payment);

    /** Two-step payment, step one: asks the ledger to hold the payment's amount on the payer's account. */
    LedgerOutcome<HoldInfo> authorize(Payment payment);

    /** Two-step payment, step two: asks the ledger to capture the payment's hold in full. Returns the ledger transaction id. */
    LedgerOutcome<UUID> capture(Payment payment);

    /** Asks the ledger to release the payment's hold. Releasing a hold that has already ended changes nothing. */
    LedgerOutcome<HoldInfo> release(Payment payment);

    /** Reads a hold's current state. */
    LedgerOutcome<HoldInfo> findHold(UUID holdId);
}