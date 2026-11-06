package com.project.paymentservice.events;

import com.project.paymentservice.payment.PaymentStatus;

/** What this service counts about the events it consumes and the safety nets behind them, so something can alert on it. */
public interface EventMetrics {

    /** The ledger and this service disagree about money: a hold or transfer contradicts the payment's status. */
    void inconsistent(PaymentStatus paymentStatus);

    /** An event could not be handled even after retries and was moved to the dead-letter topic. */
    void deadLettered(String topic);

    /** Reconciliation settled authorizations that the hold events should have settled already: the event path missed them. */
    void reconciled(int authorizations);
}