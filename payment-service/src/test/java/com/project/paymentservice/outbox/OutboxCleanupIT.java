package com.project.paymentservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.paymentservice.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class OutboxCleanupIT extends AbstractIntegrationTest {

    @Autowired
    OutboxRepository outbox;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void startFromAnEmptyOutbox() {
        jdbc.update("UPDATE outbox_events SET published_at = clock_timestamp() WHERE published_at IS NULL");
    }

    @Test
    void onlyEventsDeliveredLongAgoAreDeleted() {
        UUID old = authorizedPayment();
        UUID recent = authorizedPayment();
        UUID undelivered = authorizedPayment();
        jdbc.update("UPDATE outbox_events SET published_at = clock_timestamp() - interval '10 days' WHERE aggregate_id = ?", old);
        jdbc.update("UPDATE outbox_events SET published_at = clock_timestamp() WHERE aggregate_id = ?", recent);

        int deleted = outbox.deletePublishedOlderThan(7, 1_000);

        assertThat(deleted).isEqualTo(1);
        assertThat(outbox.findByAggregate(old)).isEmpty();
        assertThat(outbox.findByAggregate(recent)).hasSize(1);
        assertThat(outbox.findByAggregate(undelivered)).hasSize(1);
    }

    @Test
    void aPaymentsSequenceCounterIsNotAffectedByDeletingItsEvents() {
        UUID payment = authorizedPayment();
        jdbc.update("UPDATE outbox_events SET published_at = clock_timestamp() - interval '10 days' WHERE aggregate_id = ?", payment);

        outbox.deletePublishedOlderThan(7, 1_000);

        assertThat(jdbc.queryForObject("SELECT status_changes FROM payments WHERE id = ?", Integer.class, payment))
                .isEqualTo(1);
    }

    @Test
    void theBatchLimitIsRespected() {
        for (int i = 0; i < 3; i++) {
            UUID payment = authorizedPayment();
            jdbc.update("UPDATE outbox_events SET published_at = clock_timestamp() - interval '10 days' WHERE aggregate_id = ?", payment);
        }

        assertThat(outbox.deletePublishedOlderThan(7, 2)).isEqualTo(2);
        assertThat(outbox.deletePublishedOlderThan(7, 2)).isEqualTo(1);
    }

    private UUID authorizedPayment() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payments (id, idempotency_key, request_hash, from_account_id, to_account_id, "
                        + "amount_minor, currency, capture_mode) VALUES (?, ?, ?, ?, ?, 100, 'KZT', 'MANUAL')",
                id, "key-" + UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("UPDATE payments SET status = 'AUTHORIZED', ledger_hold_id = ?, "
                + "authorization_expires_at = now() + interval '1 hour' WHERE id = ?", UUID.randomUUID(), id);
        return id;
    }
}