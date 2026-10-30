package com.project.paymentservice.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.paymentservice.ledger.LedgerResult;
import com.project.paymentservice.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PaymentRetryIT extends AbstractIntegrationTest {

    @Autowired
    PaymentService payments;

    @Autowired
    PaymentRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aStalePendingPaymentIsExecutedOnceTheLedgerAnswers() {
        UUID id = pendingPayment(120);
        UUID transactionId = UUID.randomUUID();
        ledger.respondWith(payment -> new LedgerResult.Completed(transactionId));

        int tried = payments.retryPending(30, 10);

        assertThat(tried).isGreaterThanOrEqualTo(1);
        Payment payment = payments.get(id);
        assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.ledgerTransactionId()).isEqualTo(transactionId);
    }

    @Test
    void aPaymentThatStaysUnansweredWaitsBeforeTheNextTry() {
        UUID id = pendingPayment(120);
        ledger.respondWith(payment -> new LedgerResult.Unavailable("still down"));

        payments.retryPending(30, 10);
        assertThat(payments.get(id).attempts()).isEqualTo(1);

        ledger.reset();
        ledger.respondWith(payment -> new LedgerResult.Unavailable("still down"));
        payments.retryPending(30, 10);

        assertThat(ledger.calls()).extracting(Payment::id).doesNotContain(id);   // touched a moment ago: not due again
        assertThat(payments.get(id).attempts()).isEqualTo(1);
    }

    @Test
    void aFreshPendingPaymentIsLeftAloneBecauseItMayStillBeInFlight() {
        UUID id = pendingPayment(0);

        payments.retryPending(30, 10);

        assertThat(ledger.calls()).extracting(Payment::id).doesNotContain(id);
        assertThat(payments.get(id).status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void paymentsTheLedgerAlreadyAnsweredAreNotRetried() {
        UUID failed = pendingPayment(120);
        jdbc.update("UPDATE payments SET status = 'FAILED', failure_code = 'insufficient-funds' WHERE id = ?", failed);
        // a payment answered a long time ago keeps its old updated_at only if it is written directly
        assertThat(repository.findStalePending(0, 100)).doesNotContain(failed);
    }

    /** A pending payment whose last change was {@code ageSeconds} ago. */
    private UUID pendingPayment(int ageSeconds) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payments (id, idempotency_key, request_hash, from_account_id, to_account_id, "
                        + "amount_minor, currency, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 100, 'KZT', clock_timestamp() - make_interval(secs => ?))",
                id, "key-" + UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), UUID.randomUUID(), ageSeconds);
        return id;
    }
}