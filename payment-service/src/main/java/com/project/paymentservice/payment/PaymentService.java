package com.project.paymentservice.payment;

import com.project.paymentservice.common.BadRequestException;
import com.project.paymentservice.common.RequestFingerprint;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    private final PaymentRepository payments;

    public PaymentService(PaymentRepository payments) {
        this.payments = payments;
    }

    /** Registers the payment, or returns the one this idempotency key created before. */
    public CreatedPayment create(String idempotencyKey, CreatePayment command) {
        return register(idempotencyKey, command);
    }

    public Payment get(UUID id) {
        return payments.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
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