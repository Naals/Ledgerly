package com.project.paymentservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.paymentservice.common.CorrelationContext;
import com.project.paymentservice.payment.CaptureMode;
import com.project.paymentservice.payment.PaymentRepository;
import com.project.paymentservice.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=1")
class PaymentOutboxCorrelationIT extends AbstractIntegrationTest {

    @Autowired
    PaymentRepository payments;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Test
    void theTriggerStoresTheRequestContextAlongsideTheEvent() {
        UUID id = pending();
        try (CorrelationContext ignored = CorrelationContext.open("payment-request-42")) {
            payments.markCompleted(id, UUID.randomUUID());
        }

        assertThat(outbox.findByAggregate(id)).singleElement()
                .extracting(OutboxEvent::correlationId).isEqualTo("payment-request-42");
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(payload, 'correlationId') FROM outbox_events WHERE aggregate_id = ?",
                Boolean.class, id)).isFalse();
    }

    @Test
    void aBackgroundUpdateUsesThePaymentIdentity() {
        UUID id = pending();
        payments.markFailed(id, "declined");
        assertThat(outbox.findByAggregate(id)).singleElement()
                .extracting(OutboxEvent::correlationId).isEqualTo(id.toString());
    }

    @Test
    void transactionLocalMetadataDoesNotLeakToTheNextUseOfTheSameConnection() {
        UUID correlated = pending();
        try (CorrelationContext ignored = CorrelationContext.open("only-this-transaction")) {
            payments.markCompleted(correlated, UUID.randomUUID());
        }
        UUID legacy = pending();
        // A single-connection pool makes this reuse the exact connection that held the LOCAL setting.
        jdbc.update("UPDATE payments SET status = 'FAILED', failure_code = 'legacy-sql' WHERE id = ?", legacy);

        assertThat(outbox.findByAggregate(correlated)).singleElement()
                .extracting(OutboxEvent::correlationId).isEqualTo("only-this-transaction");
        assertThat(outbox.findByAggregate(legacy)).singleElement()
                .extracting(OutboxEvent::correlationId).isNull();
    }

    @Test
    void rollbackRemovesBothTheEventAndItsTransactionContext() {
        UUID id = pending();
        try (CorrelationContext ignored = CorrelationContext.open("rolled-back-request")) {
            tx.executeWithoutResult(status -> {
                payments.markCompleted(id, UUID.randomUUID());
                status.setRollbackOnly();
            });
        }
        assertThat(outbox.findByAggregate(id)).isEmpty();
        payments.markFailed(id, "declined");
        assertThat(outbox.findByAggregate(id)).singleElement()
                .extracting(OutboxEvent::correlationId).isEqualTo(id.toString());
    }

    @Test
    void correlationCannotBeAddedChangedOrRemovedAfterInsertion() {
        UUID id = pending();
        payments.markCompleted(id, UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.update("UPDATE outbox_events SET correlation_id = NULL WHERE aggregate_id = ?", id))
                .hasStackTraceContaining("outbox_event_immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE outbox_events SET correlation_id = 'other' WHERE aggregate_id = ?", id))
                .hasStackTraceContaining("outbox_event_immutable");

        UUID legacy = pending();
        jdbc.update("UPDATE payments SET status = 'FAILED', failure_code = 'legacy-sql' WHERE id = ?", legacy);
        assertThatThrownBy(() -> jdbc.update("UPDATE outbox_events SET correlation_id = 'new' WHERE aggregate_id = ?", legacy))
                .hasStackTraceContaining("outbox_event_immutable");
    }

    @Test
    void unsafeCorrelationMetadataIsRefusedByTheDatabase() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO outbox_events "
                        + "(aggregate_type, aggregate_id, event_type, payload, correlation_id) "
                        + "VALUES ('Payment', ?, 'PaymentStatusChanged', '{}', ?)", UUID.randomUUID(), "unsafe\nvalue"))
                .hasStackTraceContaining("ck_outbox_correlation_id");
    }

    private UUID pending() {
        return payments.insertIfNew("correlation-" + UUID.randomUUID(), "a".repeat(64),
                UUID.randomUUID(), UUID.randomUUID(), 100, "KZT", CaptureMode.AUTOMATIC).orElseThrow();
    }
}
