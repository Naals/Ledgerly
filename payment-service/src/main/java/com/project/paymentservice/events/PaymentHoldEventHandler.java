package com.project.paymentservice.events;

import com.project.paymentservice.ledger.HoldState;
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
 * Brings an authorized payment up to date when the ledger announces what became of its hold: captured, released or
 * expired. This is the primary way the payment hears about it when it did not cause it itself; the reconciliation job
 * is the safety net for an event that never arrives.
 *
 * <p>Most events are echoes: the payment asked for the capture or the release, recorded the outcome, and the ledger's
 * event arrives after. Those find the payment already up to date and change nothing.
 */
@Service
public class PaymentHoldEventHandler {

    static final String CONSUMER = "payment-service.hold";

    private static final Logger log = LoggerFactory.getLogger(PaymentHoldEventHandler.class);

    public enum Outcome {
        IGNORED,
        NOT_OUR_HOLD,
        ALREADY_PROCESSED,
        PAYMENT_COMPLETED,
        PAYMENT_CANCELLED,
        PAYMENT_EXPIRED,
        ALREADY_APPLIED,
        PAYMENT_INCONSISTENT
    }

    private final ProcessedEventRepository processedEvents;
    private final PaymentRepository payments;
    private final EventMetrics metrics;

    public PaymentHoldEventHandler(ProcessedEventRepository processedEvents, PaymentRepository payments,
                                   EventMetrics metrics) {
        this.processedEvents = processedEvents;
        this.payments = payments;
        this.metrics = metrics;
    }

    /**
     * Handles one ledger hold event. Remembering the event and changing the payment happen in one transaction. A hold
     * that is not one of this service's payments' holds is nobody's business and is not even remembered.
     *
     * @throws PaymentNotSettledException if the payment is still PENDING: its authorization is not recorded yet, so
     *         nothing is decided or remembered and the event is delivered again later
     */
    @Transactional
    public Outcome handle(UUID eventId, String payload) {
        HoldEvent event = HoldEventParser.parse(payload);
        HoldState state = event.state();
        if (state == HoldState.ACTIVE) {
            return Outcome.IGNORED;
        }
        Optional<Payment> payment = event.paymentId()
                .flatMap(payments::findById)
                .filter(candidate -> candidate.captureMode() == CaptureMode.MANUAL)
                // a payment has one hold; a reference that points at a payment whose hold is another one is not ours
                .filter(candidate -> candidate.ledgerHoldId() == null || candidate.ledgerHoldId().equals(event.holdId()));
        if (payment.isEmpty()) {
            return Outcome.NOT_OUR_HOLD;
        }
        if (!processedEvents.markProcessed(CONSUMER, eventId)) {
            return Outcome.ALREADY_PROCESSED;
        }

        Payment current = payment.get();
        return switch (HoldEventAction.decide(state, current.status())) {
            case COMPLETE -> {
                payments.markCaptured(current.id(), event.ledgerTransactionId());
                log.info("Payment {} was captured by ledger transaction {}", current.id(), event.ledgerTransactionId());
                yield Outcome.PAYMENT_COMPLETED;
            }
            case CANCEL -> {
                payments.markCancelled(current.id());
                yield Outcome.PAYMENT_CANCELLED;
            }
            case EXPIRE -> {
                payments.markExpired(current.id());
                yield Outcome.PAYMENT_EXPIRED;
            }
            case ALREADY_APPLIED -> Outcome.ALREADY_APPLIED;
            case NOTHING_TO_DO -> Outcome.IGNORED;
            case INCONSISTENT -> {
                log.error("The ledger says hold {} of payment {} is {}, but the payment is {}: needs attention",
                        event.holdId(), current.id(), state, current.status());
                metrics.inconsistent(current.status());
                yield Outcome.PAYMENT_INCONSISTENT;
            }
            case WAIT_FOR_AUTHORIZATION -> throw new PaymentNotSettledException(current.id());
        };
    }
}