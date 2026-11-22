package com.project.ledgercore.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.CorrelationContext;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.Currency;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=1")
class OutboxCorrelationIT extends AbstractIntegrationTest {

    @Autowired OutboxRepository outbox;
    @Autowired OutboxCorrelation correlation;
    @Autowired AccountRepository accounts;
    @Autowired CashService cash;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @Test
    void aPostingKeepsItsRequestIdAfterTheRequestContextHasEnded() {
        UUID account = accounts.create(NewAccount.customer("correlation-" + UUID.randomUUID(),
                Currency.getInstance("KZT"))).id();
        UUID transaction;
        try (CorrelationContext ignored = CorrelationContext.open("deposit-request-123")) {
            transaction = cash.deposit(account, Money.of(100, "KZT"));
        }

        OutboxEvent stored = outbox.findByAggregate(transaction).getFirst();
        assertThat(stored.correlationId()).isEqualTo("deposit-request-123");
        try (CorrelationContext ignored = CorrelationContext.open("unrelated-relay-run")) {
            EventRecordSpec record = EventRecordSpec.of(stored, Map.of("TransactionPosted", "ledger.events"));
            assertThat(record.headers()).containsEntry("correlation-id", "deposit-request-123");
        }
    }

    @Test
    void aConnectionDoesNotRetainRequestMetadataAfterCommitOrRollback() {
        for (boolean rollback : new boolean[]{false, true}) {
            try (CorrelationContext ignored = CorrelationContext.open("temporary-request")) {
                tx.executeWithoutResult(status -> {
                    correlation.bind();
                    assertThat(setting()).isEqualTo("temporary-request");
                    if (rollback) {
                        status.setRollbackOnly();
                    }
                });
            }
            // The one-connection pool forces reuse of the connection that carried the metadata.
            assertThat(setting()).isNull();
        }
    }

    @Test
    void bindingAnUncorrelatedOperationClearsAnEarlierContextWithinTheTransaction() {
        tx.executeWithoutResult(status -> {
            try (CorrelationContext ignored = CorrelationContext.open("earlier-operation")) {
                correlation.bind();
            }
            correlation.bind();
            assertThat(setting()).isNull();
        });
    }

    @Test
    void correlationCannotBeChangedIncludingTransitionsToAndFromNull() {
        UUID aggregate = UUID.randomUUID();
        outbox.add("test", aggregate, "TestEvent", "{}");
        long legacy = outbox.findByAggregate(aggregate).getFirst().seq();
        assertThatThrownBy(() -> jdbc.update("UPDATE outbox_events SET correlation_id = 'forged' WHERE seq = ?", legacy))
                .hasStackTraceContaining("outbox_event_immutable");

        UUID correlatedAggregate = UUID.randomUUID();
        try (CorrelationContext ignored = CorrelationContext.open("original-request")) {
            outbox.add("test", correlatedAggregate, "TestEvent", "{}");
        }
        long correlated = outbox.findByAggregate(correlatedAggregate).getFirst().seq();
        assertThatThrownBy(() -> jdbc.update("UPDATE outbox_events SET correlation_id = NULL WHERE seq = ?", correlated))
                .hasStackTraceContaining("outbox_event_immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE outbox_events SET correlation_id = 'changed' WHERE seq = ?", correlated))
                .hasStackTraceContaining("outbox_event_immutable");
    }

    @Test
    void directSqlCannotStoreUnsafeCorrelationMetadata() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO outbox_events "
                        + "(aggregate_type, aggregate_id, event_type, payload, correlation_id) "
                        + "VALUES ('test', ?, 'TestEvent', '{}'::jsonb, ?)", UUID.randomUUID(), "bad\nrequest"))
                .hasStackTraceContaining("ck_outbox_correlation_id");
    }

    private String setting() {
        return jdbc.queryForObject("SELECT NULLIF(current_setting('ledgerly.correlation_id', true), '')", String.class);
    }
}
