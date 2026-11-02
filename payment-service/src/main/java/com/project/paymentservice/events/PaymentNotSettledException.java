package com.project.paymentservice.events;

import java.util.UUID;

/**
 * The ledger reversed a payment this service has not yet seen complete. It is thrown so the event's transaction
 * rolls back (the event is not recorded as processed) and Kafka delivers the event again later.
 */
public class PaymentNotSettledException extends RuntimeException {

    public PaymentNotSettledException(UUID paymentId) {
        super("Payment " + paymentId + " is still PENDING; its reversal event will be delivered again");
    }
}