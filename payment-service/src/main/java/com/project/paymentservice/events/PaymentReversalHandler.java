package com.project.paymentservice.events;

import com.project.paymentservice.payment.Payment;
import com.project.paymentservice.payment.PaymentRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Makes a payment REVERSED when the ledger reverses the transfer that paid it. */
@Service
public class PaymentReversalHandler {

    static final String CONSUMER = "payment-service";

    private static final Logger log = LoggerFactory.getLogger(PaymentReversalHandler.class);

    public enum Outcome {
        NOT_A_REVERSAL,
        ALREADY_PROCESSED,
        NOT_OUR_PAYMENT,
        TRANSACTION_MISMATCH,
        PAYMENT_REVERSED,
        PAYMENT_ALREADY_REVERSED,
        PAYMENT_NOT_REVERSIBLE
    }

    private final ProcessedEventRepository processedEvents;
    private final PaymentRepository payments;

    public PaymentReversalHandler(ProcessedEventRepository processedEvents, PaymentRepository payments) {
        this.processedEvents = processedEvents;
        this.payments = payments;
    }

    /**
     * Handles one ledger event. Remembering the event and changing the payment happen in one transaction, so a
     * redelivered event is recognised and ignored, and a crash in between leaves neither half done.
     *
     * @throws PaymentNotSettledException if the payment is still PENDING or AUTHORIZED: the ledger can be ahead of this service,
     *         so nothing is recorded and the event is delivered again later
     */
    @Transactional
    public Outcome handle(UUID eventId, String payload) {
        LedgerEvent event = LedgerEventParser.parse(payload);
        if (!event.isReversal()) {
            return Outcome.NOT_A_REVERSAL;
        }
        if (!processedEvents.markProcessed(CONSUMER, eventId)) {
            return Outcome.ALREADY_PROCESSED;
        }

        Optional<Payment> payment = event.paymentId().flatMap(payments::findById);
        if (payment.isEmpty()) {
            return Outcome.NOT_OUR_PAYMENT;
        }

        Payment matched = payment.get();
        UUID paymentId = matched.id();
        ReversalAction action = ReversalAction.forPayment(matched.status());
        // A reference finds a candidate payment, but callers can reuse that reference on unrelated transfers.
        // Wait for an unsettled payment to learn its transaction before deciding whether this event belongs to it.
        if ((action == ReversalAction.REVERSE || action == ReversalAction.ALREADY_REVERSED)
                && (event.reversesTransactionId() == null
                    || !event.reversesTransactionId().equals(matched.ledgerTransactionId()))) {
            return Outcome.TRANSACTION_MISMATCH;
        }
        return switch (action) {
            case REVERSE -> {
                payments.markReversed(paymentId);
                log.info("Payment {} was reversed by ledger transaction {}", paymentId, event.transactionId());
                yield Outcome.PAYMENT_REVERSED;
            }
            case ALREADY_REVERSED -> Outcome.PAYMENT_ALREADY_REVERSED;
            case NOT_REVERSIBLE -> Outcome.PAYMENT_NOT_REVERSIBLE;
            case WAIT_FOR_PAYMENT -> throw new PaymentNotSettledException(paymentId);
        };
    }
}
