package com.project.paymentservice.events;

import com.project.paymentservice.payment.PaymentStatus;

/** What this service counts about the events it consumes, so something can alert on it. */
public interface EventMetrics {

    /** The ledger moved money for a payment that FAILED, was CANCELLED or EXPIRED: the two services disagree. */
    void inconsistent(PaymentStatus paymentStatus);

    /** An event could not be handled even after retries and was moved to the dead-letter topic. */
    void deadLettered(String topic);
}