package com.project.paymentservice.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.paymentservice.support.AbstractIntegrationTest;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PaymentRepositoryTwoStepIT extends AbstractIntegrationTest {

    @Autowired
    PaymentRepository repository;

    private UUID newPayment(CaptureMode mode) {
        return repository.insertIfNew("key-" + UUID.randomUUID(), "a".repeat(64),
                UUID.randomUUID(), UUID.randomUUID(), 100, "KZT", mode).orElseThrow();
    }

    private UUID authorizedPayment(OffsetDateTime expiresAt) {
        UUID id = newPayment(CaptureMode.MANUAL);
        repository.markAuthorized(id, UUID.randomUUID(), expiresAt);
        return id;
    }

    @Test
    void aPaymentIsStoredWithItsModeAndReadsBackWithEveryNewColumn() {
        UUID hold = UUID.randomUUID();
        UUID id = newPayment(CaptureMode.MANUAL);
        repository.markAuthorized(id, hold, OffsetDateTime.now().plusHours(1));

        Payment payment = repository.findById(id).orElseThrow();

        assertThat(payment.captureMode()).isEqualTo(CaptureMode.MANUAL);
        assertThat(payment.status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payment.ledgerHoldId()).isEqualTo(hold);
        assertThat(payment.authorizationExpiresAt()).isNotNull();
        assertThat(payment.ledgerTransactionId()).isNull();
        assertThat(repository.findById(newPayment(CaptureMode.AUTOMATIC)).orElseThrow().captureMode())
                .isEqualTo(CaptureMode.AUTOMATIC);
    }

    @Test
    void markAuthorizedOnlyChangesAPendingPayment() {
        UUID id = newPayment(CaptureMode.MANUAL);

        assertThat(repository.markAuthorized(id, UUID.randomUUID(), OffsetDateTime.now().plusHours(1))).isTrue();
        assertThat(repository.markAuthorized(id, UUID.randomUUID(), OffsetDateTime.now().plusHours(1))).isFalse();
    }

    @Test
    void markCapturedOnlyChangesAnAuthorizedPaymentAndRecordsTheTransaction() {
        UUID pending = newPayment(CaptureMode.MANUAL);
        UUID authorized = authorizedPayment(OffsetDateTime.now().plusHours(1));
        UUID transaction = UUID.randomUUID();

        assertThat(repository.markCaptured(pending, UUID.randomUUID())).isFalse();
        assertThat(repository.markCaptured(authorized, transaction)).isTrue();
        assertThat(repository.markCaptured(authorized, UUID.randomUUID())).isFalse();

        Payment payment = repository.findById(authorized).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.ledgerTransactionId()).isEqualTo(transaction);
        assertThat(payment.ledgerHoldId()).isNotNull();
    }

    @Test
    void cancellingAndExpiringOnlyChangeAnAuthorizedPaymentAndOnlyOneOfThemWins() {
        UUID cancelled = authorizedPayment(OffsetDateTime.now().plusHours(1));
        UUID expired = authorizedPayment(OffsetDateTime.now().plusHours(1));

        assertThat(repository.markCancelled(cancelled)).isTrue();
        assertThat(repository.markExpired(cancelled)).isFalse();
        assertThat(repository.markExpired(expired)).isTrue();
        assertThat(repository.markCancelled(expired)).isFalse();

        assertThat(repository.findById(cancelled).orElseThrow().status()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(repository.findById(expired).orElseThrow().status()).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test
    void aCaptureAndACancelRacingOnOnePaymentHaveExactlyOneWinner() throws Exception {
        for (int round = 0; round < 10; round++) {
            UUID id = authorizedPayment(OffsetDateTime.now().plusHours(1));
            var results = com.project.paymentservice.support.ConcurrentRunner.runAll(2, java.util.List.of(
                    () -> repository.markCaptured(id, UUID.randomUUID()),
                    () -> repository.markCancelled(id)));

            assertThat(results.stream().filter(won -> (boolean) won).count()).as("round " + round).isEqualTo(1);
        }
    }

    @Test
    void onlyAuthorizedPaymentsPastTheirExpiryAreDue() {
        UUID due = authorizedPayment(OffsetDateTime.now().minusMinutes(5));
        UUID later = authorizedPayment(OffsetDateTime.now().plusHours(1));
        UUID cancelled = authorizedPayment(OffsetDateTime.now().minusMinutes(5));
        repository.markCancelled(cancelled);

        assertThat(repository.findDueAuthorizations(1_000)).contains(due).doesNotContain(later, cancelled);
    }
}