package com.project.paymentservice.payment;

import com.project.paymentservice.common.BadRequestException;
import com.project.paymentservice.common.RequestFingerprint;
import com.project.paymentservice.ledger.LedgerGateway;
import com.project.paymentservice.ledger.LedgerResult;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Registers payments and executes them through the ledger.
 *
 * <p>No database transaction is held while the ledger is called: the payment is registered (committed), the
 * ledger is asked, and the outcome is recorded. If the service dies in between, the payment simply stays
 * PENDING and is executed again later, which is safe because the ledger deduplicates on the payment id.
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
     * Asks the ledger to execute a pending payment and records the outcome. Safe to call again for the same
     * payment, even concurrently: the ledger deduplicates, and only one outcome can be recorded.
     */
    public Payment execute(UUID paymentId) {
        Payment payment = get(paymentId);
        if (payment.status() != PaymentStatus.PENDING) {
            return payment;
        }

        LedgerResult result = ledger.transfer(payment);
        switch (result) {
            case LedgerResult.Completed completed -> payments.markCompleted(paymentId, completed.transactionId());
            case LedgerResult.Rejected rejected -> payments.markFailed(paymentId, rejected.code());
            case LedgerResult.Unavailable unavailable -> {
                log.warn("Ledger could not answer for payment {}: {}", paymentId, unavailable.reason());
                payments.recordAttempt(paymentId);
            }
        }
        return get(paymentId);
    }

    private CreatedPayment register(String idempotencyKey, CreatePayment command) {
        if (command.fromAccountId().equals(command.toAccountId())) {
            throw new BadRequestException("same-account", "A payment needs two different accounts");
        }
        String hash = RequestFingerprint.of(
                command.fromAccountId().toString(),
                command.toAccountId().toString(),
                Long.toString(command.amountMinor()),
                command.currency());

        Optional<UUID> created = payments.insertIfNew(idempotencyKey, hash, command.fromAccountId(),
                command.toAccountId(), command.amountMinor(), command.currency());
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