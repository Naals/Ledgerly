package com.project.paymentservice.events;

import java.util.UUID;

/**
 * The ledger reported something about a payment this service has not yet seen settle. It is thrown so the event's
 * transaction rolls back (the event is not recorded as processed) and Kafka delivers the event again later.
 */
public class PaymentNotSettledException extends RuntimeException {

    public PaymentNotSettledException(UUID paymentId) {
        super("Payment " + paymentId + " has not caught up with the ledger yet; the event will be delivered again");
    }
}