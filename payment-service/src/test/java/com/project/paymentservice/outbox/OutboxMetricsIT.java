package com.project.paymentservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.paymentservice.support.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class OutboxMetricsIT extends AbstractIntegrationTest {

    @Autowired
    MeterRegistry registry;

    @Autowired
    OutboxPublisher publisher;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void startFromAnEmptyOutbox() {
        jdbc.update("UPDATE outbox_events SET published_at = clock_timestamp() WHERE published_at IS NULL");
    }

    @Test
    void waitingEventsAreCountedAndTheOldestOnesAgeIsReported() {
        assertThat(pending()).isZero();
        assertThat(oldestAge()).isZero();

        authorizedPayment();
        authorizedPayment();

        assertThat(pending()).isEqualTo(2);
        assertThat(oldestAge()).isGreaterThan(0);
    }

    @Test
    void deliveringTheEventsBringsBothBackToZero() {
        authorizedPayment();

        publisher.publishBatch(100);

        assertThat(pending()).isZero();
        assertThat(oldestAge()).isZero();
    }

    private double pending() {
        return registry.get("payments.outbox.pending").gauge().value();
    }

    private double oldestAge() {
        return registry.get("payments.outbox.oldest.pending.age").gauge().value();
    }

    private void authorizedPayment() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payments (id, idempotency_key, request_hash, from_account_id, to_account_id, "
                        + "amount_minor, currency, capture_mode) VALUES (?, ?, ?, ?, ?, 100, 'KZT', 'MANUAL')",
                id, "key-" + UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("UPDATE payments SET status = 'AUTHORIZED', ledger_hold_id = ?, "
                + "authorization_expires_at = now() + interval '1 hour' WHERE id = ?", UUID.randomUUID(), id);
    }
}