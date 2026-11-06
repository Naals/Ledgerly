package com.project.paymentservice.events;

import com.project.paymentservice.payment.CaptureMode;
import com.project.paymentservice.payment.Payment;
import com.project.paymentservice.payment.PaymentRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Completes an authorized payment when the ledger announces the transfer that captured it.
 *
 * <p>Normally the capture call's own answer completes the payment. If that answer is lost, this is how the payment
 * finds out: the capture's transfer carries the payment id as its reference (Day 8), so the event names the payment.
 */
@Service
public class PaymentCaptureHandler {

    static final String CONSUMER = "payment-service.capture";

    private static final Logger log = LoggerFactory.getLogger(PaymentCaptureHandler.class);

    public enum Outcome {
        NOT_A_TRANSFER,
        NOT_A_CAPTURE,
        ALREADY_PROCESSED,
        PAYMENT_COMPLETED,
        PAYMENT_ALREADY_COMPLETED,
        PAYMENT_INCONSISTENT
    }

    private final ProcessedEventRepository processedEvents;
    private final PaymentRepository payments;
    private final EventMetrics metrics;

    public PaymentCaptureHandler(ProcessedEventRepository processedEvents, PaymentRepository payments,
                                 EventMetrics metrics) {
        this.processedEvents = processedEvents;
        this.payments = payments;
        this.metrics = metrics;
    }

    /**
     * Handles one ledger event. Most transfers on the ledger are nobody's business here, so an event is only
     * remembered once it is known to be the capture of one of this service's manual payments.
     *
     * @throws PaymentNotSettledException if the payment is still PENDING: its authorization is not recorded yet, so
     *         nothing is decided or remembered and the event is delivered again later
     */
    @Transactional
    public Outcome handle(UUID eventId, String payload) {
        LedgerEvent event = LedgerEventParser.parse(payload);
        if (!event.isTransfer()) {
            return Outcome.NOT_A_TRANSFER;
        }
        Optional<Payment> payment = event.paymentId()
                .flatMap(payments::findById)
                .filter(candidate -> candidate.captureMode() == CaptureMode.MANUAL);
        if (payment.isEmpty()) {
            return Outcome.NOT_A_CAPTURE;
        }
        if (!processedEvents.markProcessed(CONSUMER, eventId)) {
            return Outcome.ALREADY_PROCESSED;
        }

        Payment captured = payment.get();
        return switch (CaptureEventAction.forPayment(captured.status())) {
            case COMPLETE -> {
                payments.markCaptured(captured.id(), event.transactionId());
                log.info("Payment {} was captured by ledger transaction {}", captured.id(), event.transactionId());
                yield Outcome.PAYMENT_COMPLETED;
            }
            case ALREADY_COMPLETED -> Outcome.PAYMENT_ALREADY_COMPLETED;
            case INCONSISTENT -> {
                log.error("The ledger moved money (transaction {}) for payment {}, which is {}: needs attention",
                        event.transactionId(), captured.id(), captured.status());
                metrics.inconsistent(captured.status());
                yield Outcome.PAYMENT_INCONSISTENT;
            }
            case WAIT_FOR_AUTHORIZATION -> throw new PaymentNotSettledException(captured.id());
        };
    }
}