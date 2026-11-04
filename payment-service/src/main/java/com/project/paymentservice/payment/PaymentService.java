package com.project.paymentservice.payment;

import com.project.paymentservice.common.BadRequestException;
import com.project.paymentservice.common.BusinessRuleException;
import com.project.paymentservice.common.RequestFingerprint;
import com.project.paymentservice.common.ServiceUnavailableException;
import com.project.paymentservice.ledger.HoldInfo;
import com.project.paymentservice.ledger.LedgerGateway;
import com.project.paymentservice.ledger.LedgerOutcome;
import com.project.paymentservice.ledger.LedgerResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Registers payments and executes them through the ledger, in one step or two.
 *
 * <p>No database transaction is held while the ledger is called: the payment is registered (committed), the
 * ledger is asked, and the outcome is recorded. If the service dies in between, the payment simply stays
 * where it was and the step is asked again later, which is safe because the ledger deduplicates on the payment id.
 *
 * <p>Whatever this service believes about a hold, the ledger knows better. When the two disagree (a capture
 * whose answer was lost, a hold that expired on its own), the service asks the ledger and records the truth.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final LedgerGateway ledger;

    public PaymentService(PaymentRepository payments, LedgerGateway ledger) {
        this.payments = payments;
        this.ledger = ledger;
    }

    /** Registers the payment (or finds the one this key created) and executes it if it is still pending. */
    public CreatedPayment create(String idempotencyKey, CreatePayment command) {
        CreatedPayment registered = register(idempotencyKey, command);
        Payment payment = registered.payment();
        if (payment.status() == PaymentStatus.PENDING) {
            payment = execute(payment.id());
        }
        return new CreatedPayment(payment, registered.replayed());
    }

    public Payment get(UUID id) {
        return payments.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }

    /**
     * Asks the ledger for the first step of a pending payment (the whole transfer, or the hold) and records the
     * outcome. Safe to call again for the same payment, even concurrently: the ledger deduplicates, and only
     * one outcome can be recorded.
     */
    public Payment execute(UUID paymentId) {
        Payment payment = get(paymentId);
        if (payment.status() != PaymentStatus.PENDING) {
            return payment;
        }
        switch (payment.captureMode()) {
            case AUTOMATIC -> transfer(payment);
            case MANUAL -> authorize(payment);
        }
        return get(paymentId);
    }

    /** Executes pending payments the ledger has not answered for. Returns how many it tried. */
    public int retryPending(int olderThanSeconds, int limit) {
        List<UUID> stale = payments.findStalePending(olderThanSeconds, limit);
        for (UUID id : stale) {
            try {
                execute(id);
            } catch (RuntimeException e) {
                log.warn("Retrying payment {} failed", id, e);
            }
        }
        return stale.size();
    }

    private void transfer(Payment payment) {
        LedgerResult result = ledger.transfer(payment);
        switch (result) {
            case LedgerResult.Completed completed -> payments.markCompleted(payment.id(), completed.transactionId());
            case LedgerResult.Rejected rejected -> payments.markFailed(payment.id(), rejected.code());
            case LedgerResult.Unavailable unavailable -> {
                log.warn("Ledger could not answer for payment {}: {}", payment.id(), unavailable.reason());
                payments.recordAttempt(payment.id());
            }
        }
    }

    private void authorize(Payment payment) {
        LedgerOutcome<HoldInfo> outcome = ledger.authorize(payment);
        switch (outcome) {
            case LedgerOutcome.Done<HoldInfo> done ->
                    payments.markAuthorized(payment.id(), done.value().id(), done.value().expiresAt());
            case LedgerOutcome.Rejected<HoldInfo> rejected -> payments.markFailed(payment.id(), rejected.code());
            case LedgerOutcome.Unavailable<HoldInfo> unavailable -> {
                log.warn("Ledger could not answer for payment {}: {}", payment.id(), unavailable.reason());
                payments.recordAttempt(payment.id());
            }
        }
    }

    private CreatedPayment register(String idempotencyKey, CreatePayment command) {
        if (command.fromAccountId().equals(command.toAccountId())) {
            throw new BadRequestException("same-account", "A payment needs two different accounts");
        }
        // The mode joins the fingerprint only when it is not the default, so a key used before two-step payments
        // existed still matches the same request replayed after.
        List<String> parts = new ArrayList<>(List.of(
                command.fromAccountId().toString(),
                command.toAccountId().toString(),
                Long.toString(command.amountMinor()),
                command.currency()));
        if (command.captureMode() == CaptureMode.MANUAL) {
            parts.add(CaptureMode.MANUAL.name());
        }
        String hash = RequestFingerprint.of(parts.toArray(String[]::new));

        Optional<UUID> created = payments.insertIfNew(idempotencyKey, hash, command.fromAccountId(),
                command.toAccountId(), command.amountMinor(), command.currency(), command.captureMode());
        if (created.isPresent()) {
            return new CreatedPayment(get(created.get()), false);
        }

        Payment existing = payments.findByIdempotencyKey(idempotencyKey).orElseThrow();
        if (!existing.requestHash().equals(hash)) {
            throw new IdempotencyKeyReuseException(idempotencyKey);
        }
        return new CreatedPayment(existing, true);
    }

    /**
     * Captures an authorized payment: the ledger moves the held money. Capturing a payment that is already
     * COMPLETED returns it unchanged. If the ledger cannot answer, the payment stays AUTHORIZED and the same
     * call can simply be repeated.
     */
    public Payment capture(UUID paymentId) {
        Payment payment = get(paymentId);
        if (payment.captureMode() != CaptureMode.MANUAL) {
            throw new PaymentNotCapturableException(paymentId, "it was paid in one step (AUTOMATIC)");
        }
        switch (payment.status()) {
            case COMPLETED -> {
                return payment;
            }
            case AUTHORIZED -> {
                // go on
            }
            default -> throw new PaymentNotCapturableException(paymentId, "it is " + payment.status());
        }

        LedgerOutcome<UUID> outcome = ledger.capture(payment);
        return switch (outcome) {
            case LedgerOutcome.Done<UUID> done -> {
                payments.markCaptured(paymentId, done.value());
                yield get(paymentId);
            }
            case LedgerOutcome.Rejected<UUID> rejected -> afterRefusedCapture(payment, rejected.code());
            case LedgerOutcome.Unavailable<UUID> unavailable -> throw ledgerUnavailable("capture", unavailable.reason());
        };
    }

    /**
     * Asks the ledger what became of an authorized payment's hold and records it: captured (the payment is
     * COMPLETED), released (CANCELLED) or expired (EXPIRED). This is how the service catches up when it was not
     * told, for example when the answer to a capture was lost.
     */
    public Settlement settle(Payment payment) {
        if (payment.status() != PaymentStatus.AUTHORIZED) {
            return Settlement.NOT_AUTHORIZED;
        }
        LedgerOutcome<HoldInfo> outcome = ledger.findHold(payment.ledgerHoldId());
        return switch (outcome) {
            case LedgerOutcome.Done<HoldInfo> done -> record(payment, done.value());
            case LedgerOutcome.Rejected<HoldInfo> rejected -> {
                log.error("The ledger does not know the hold {} of payment {}: {}",
                        payment.ledgerHoldId(), payment.id(), rejected.code());
                yield Settlement.UNKNOWN_HOLD;
            }
            case LedgerOutcome.Unavailable<HoldInfo> unavailable -> Settlement.LEDGER_UNAVAILABLE;
        };
    }

    /** The ledger refused to capture. Some refusals mean the hold's fate was decided elsewhere: find out which. */
    private Payment afterRefusedCapture(Payment payment, String code) {
        switch (code) {
            case "hold-expired" -> {
                payments.markExpired(payment.id());
                throw new PaymentNotCapturableException(payment.id(), "its authorization has expired");
            }
            case "hold-not-active" -> {
                settle(payment);
                Payment now = get(payment.id());
                if (now.status() == PaymentStatus.COMPLETED) {
                    return now;
                }
                throw new PaymentNotCapturableException(payment.id(), "it is " + now.status());
            }
            default -> throw new BusinessRuleException(code, "The ledger refused the capture: " + code);
        }
    }

    private Settlement record(Payment payment, HoldInfo hold) {
        switch (hold.state()) {
            case ACTIVE -> {
                return Settlement.STILL_ACTIVE;
            }
            case CAPTURED -> {
                if (hold.ledgerTransactionId() == null) {
                    log.error("The ledger says hold {} of payment {} is captured but gave no transaction",
                            hold.id(), payment.id());
                    return Settlement.UNKNOWN_HOLD;
                }
                payments.markCaptured(payment.id(), hold.ledgerTransactionId());
            }
            case RELEASED -> payments.markCancelled(payment.id());
            case EXPIRED -> payments.markExpired(payment.id());
        }
        return Settlement.SETTLED;
    }

    private ServiceUnavailableException ledgerUnavailable(String action, String reason) {
        log.warn("The ledger could not {} a payment: {}", action, reason);
        return new ServiceUnavailableException("ledger-unavailable",
                "The ledger cannot " + action + " the payment right now; the same request can be repeated");
    }
}