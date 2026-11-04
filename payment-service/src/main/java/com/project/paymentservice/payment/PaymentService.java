package com.project.paymentservice.payment;

import com.project.paymentservice.common.BadRequestException;
import com.project.paymentservice.common.RequestFingerprint;
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
}