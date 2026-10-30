package com.project.paymentservice.events;

import com.project.paymentservice.payment.PaymentRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Makes a payment REVERSED when the ledger reverses the transaction that paid it. */
@Service
public class PaymentReversalHandler {

    static final String CONSUMER = "payment-service";

    private static final Logger log = LoggerFactory.getLogger(PaymentReversalHandler.class);

    public enum Outcome {
        NOT_A_REVERSAL,
        ALREADY_PROCESSED,
        NOT_OUR_PAYMENT,
        PAYMENT_REVERSED
    }

    private final ProcessedEventRepository processedEvents;
    private final PaymentRepository payments;

    public PaymentReversalHandler(ProcessedEventRepository processedEvents, PaymentRepository payments) {
        this.processedEvents = processedEvents;
        this.payments = payments;
    }

    /**
     * Handles one ledger event. Remembering the event and changing the payment happen in one transaction, so
     * a redelivered event is recognised and ignored, and a crash in between leaves neither half done.
     */
    @Transactional
    public Outcome handle(UUID eventId, String payload) {
        LedgerEvent event = LedgerEventParser.parse(payload);
        if (!event.isReversal() || event.reversesTransactionId() == null) {
            return Outcome.NOT_A_REVERSAL;
        }
        if (!processedEvents.markProcessed(CONSUMER, eventId)) {
            return Outcome.ALREADY_PROCESSED;
        }
        int reversed = payments.markReversedByLedgerTransaction(event.reversesTransactionId());
        if (reversed == 0) {
            return Outcome.NOT_OUR_PAYMENT;
        }
        log.info("Payment made by ledger transaction {} was reversed by transaction {}",
                event.reversesTransactionId(), event.transactionId());
        return Outcome.PAYMENT_REVERSED;
    }
}