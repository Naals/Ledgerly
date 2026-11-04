package com.project.paymentservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.paymentservice.support.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class PaymentOutboxSchemaIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Test
    void registeringAPaymentEmitsNothing() {
        UUID id = newPayment("MANUAL");

        assertThat(statuses(id)).isEmpty();
    }

    @Test
    void everyStatusChangeEmitsOneEventWithItsPreviousStatusAndSequence() {
        UUID id = newPayment("MANUAL");
        authorize(id);
        jdbc.update("UPDATE payments SET status = 'COMPLETED', ledger_transaction_id = ? WHERE id = ?",
                UUID.randomUUID(), id);
        jdbc.update("UPDATE payments SET status = 'REVERSED' WHERE id = ?", id);

        assertThat(statuses(id)).containsExactly("AUTHORIZED", "COMPLETED", "REVERSED");
        assertThat(jdbc.queryForList("SELECT payload->>'previousStatus' FROM outbox_events "
                + "WHERE aggregate_id = ? ORDER BY seq", String.class, id))
                .containsExactly("PENDING", "AUTHORIZED", "COMPLETED");
        assertThat(jdbc.queryForList("SELECT (payload->>'sequence')::int FROM outbox_events "
                + "WHERE aggregate_id = ? ORDER BY seq", Integer.class, id)).containsExactly(1, 2, 3);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT event_type) FROM outbox_events WHERE aggregate_id = ?",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void anUpdateThatDoesNotChangeTheStatusEmitsNothing() {
        UUID id = newPayment("AUTOMATIC");

        jdbc.update("UPDATE payments SET attempts = attempts + 1 WHERE id = ?", id);
        jdbc.update("UPDATE payments SET status = status WHERE id = ?", id);

        assertThat(statuses(id)).isEmpty();
    }

    @Test
    void aRolledBackTransitionLeavesNoEventAndNoCount() {
        UUID id = newPayment("MANUAL");

        tx.executeWithoutResult(status -> {
            authorize(id);
            status.setRollbackOnly();
        });

        assertThat(statuses(id)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status_changes FROM payments WHERE id = ?", Integer.class, id)).isZero();
    }

    @Test
    void aRefusedTransitionLeavesNoEvent() {
        UUID id = newPayment("MANUAL");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payments SET status = 'COMPLETED', ledger_transaction_id = ? WHERE id = ?", UUID.randomUUID(), id))
                .hasStackTraceContaining("invalid_payment_transition");

        assertThat(statuses(id)).isEmpty();
    }

    @Test
    void aFailureCarriesItsCodeAndNoExpiry() {
        UUID id = newPayment("AUTOMATIC");
        jdbc.update("UPDATE payments SET status = 'FAILED', failure_code = 'insufficient-funds' WHERE id = ?", id);

        assertThat(jdbc.queryForObject("SELECT payload->>'failureCode' FROM outbox_events WHERE aggregate_id = ?",
                String.class, id)).isEqualTo("insufficient-funds");
        assertThat(jdbc.queryForObject("SELECT payload->'authorizationExpiresAt' = 'null'::jsonb "
                + "FROM outbox_events WHERE aggregate_id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    void anEventCannotBeEditedAndAnUndeliveredOneCannotBeDeleted() {
        UUID id = newPayment("MANUAL");
        authorize(id);
        long seq = jdbc.queryForObject("SELECT seq FROM outbox_events WHERE aggregate_id = ?", Long.class, id);

        assertThatThrownBy(() -> jdbc.update("UPDATE outbox_events SET payload = '{}' WHERE seq = ?", seq))
                .hasStackTraceContaining("outbox_event_immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE outbox_events SET event_type = 'X' WHERE seq = ?", seq))
                .hasStackTraceContaining("outbox_event_immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM outbox_events WHERE seq = ?", seq))
                .hasStackTraceContaining("outbox_unpublished_event");
    }

    @Test
    void deliveryBookkeepingMayChangeAndADeliveredEventMayBeDeleted() {
        UUID id = newPayment("MANUAL");
        authorize(id);
        long seq = jdbc.queryForObject("SELECT seq FROM outbox_events WHERE aggregate_id = ?", Long.class, id);

        assertThat(jdbc.update("UPDATE outbox_events SET attempts = attempts + 1, last_error = 'x', "
                + "next_attempt_at = now() WHERE seq = ?", seq)).isEqualTo(1);
        assertThat(jdbc.update("UPDATE outbox_events SET published_at = clock_timestamp() WHERE seq = ?", seq)).isEqualTo(1);
        assertThat(jdbc.update("DELETE FROM outbox_events WHERE seq = ?", seq)).isEqualTo(1);
    }

    private UUID newPayment(String mode) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payments (id, idempotency_key, request_hash, from_account_id, to_account_id, "
                        + "amount_minor, currency, capture_mode) VALUES (?, ?, ?, ?, ?, 100, 'KZT', ?)",
                id, "key-" + UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), UUID.randomUUID(), mode);
        return id;
    }

    private void authorize(UUID id) {
        jdbc.update("UPDATE payments SET status = 'AUTHORIZED', ledger_hold_id = ?, "
                + "authorization_expires_at = now() + interval '1 hour' WHERE id = ?", UUID.randomUUID(), id);
    }

    private List<String> statuses(UUID id) {
        return jdbc.queryForList("SELECT payload->>'status' FROM outbox_events WHERE aggregate_id = ? ORDER BY seq",
                String.class, id);
    }
}