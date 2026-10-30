package com.project.paymentservice.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.paymentservice.events.PaymentReversalHandler.Outcome;
import com.project.paymentservice.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PaymentReversalHandlerIT extends AbstractIntegrationTest {

    @Autowired
    PaymentReversalHandler handler;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aReversalEventReversesTheCompletedPayment() {
        UUID ledgerTransaction = UUID.randomUUID();
        UUID paymentId = completedPayment(ledgerTransaction);

        Outcome outcome = handler.handle(UUID.randomUUID(), reversalOf(ledgerTransaction));

        assertThat(outcome).isEqualTo(Outcome.PAYMENT_REVERSED);
        assertThat(statusOf(paymentId)).isEqualTo("REVERSED");
    }

    @Test
    void deliveringTheSameEventAgainChangesNothing() {
        UUID ledgerTransaction = UUID.randomUUID();
        UUID paymentId = completedPayment(ledgerTransaction);
        UUID eventId = UUID.randomUUID();
        handler.handle(eventId, reversalOf(ledgerTransaction));

        Outcome again = handler.handle(eventId, reversalOf(ledgerTransaction));

        assertThat(again).isEqualTo(Outcome.ALREADY_PROCESSED);
        assertThat(statusOf(paymentId)).isEqualTo("REVERSED");
    }

    @Test
    void aReversalOfSomeoneElsesTransactionIsRecordedButChangesNoPayment() {
        UUID paymentId = completedPayment(UUID.randomUUID());

        Outcome outcome = handler.handle(UUID.randomUUID(), reversalOf(UUID.randomUUID()));

        assertThat(outcome).isEqualTo(Outcome.NOT_OUR_PAYMENT);
        assertThat(statusOf(paymentId)).isEqualTo("COMPLETED");
    }

    @Test
    void eventsThatAreNotReversalsAreIgnoredAndNotRecorded() {
        Long before = jdbc.queryForObject("SELECT count(*) FROM processed_events", Long.class);

        Outcome outcome = handler.handle(UUID.randomUUID(), """
                {"version": 1, "transactionId": "%s", "type": "TRANSFER", "reversesTransactionId": null}
                """.formatted(UUID.randomUUID()));

        assertThat(outcome).isEqualTo(Outcome.NOT_A_REVERSAL);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Long.class)).isEqualTo(before);
    }

    static String reversalOf(UUID ledgerTransaction) {
        return """
                {"version": 1, "transactionId": "%s", "type": "REVERSAL", "reversesTransactionId": "%s"}
                """.formatted(UUID.randomUUID(), ledgerTransaction);
    }

    private UUID completedPayment(UUID ledgerTransaction) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payments (id, idempotency_key, request_hash, from_account_id, to_account_id, "
                        + "amount_minor, currency) VALUES (?, ?, ?, ?, ?, 100, 'KZT')",
                id, "key-" + UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("UPDATE payments SET status = 'COMPLETED', ledger_transaction_id = ? WHERE id = ?",
                ledgerTransaction, id);
        return id;
    }

    private String statusOf(UUID paymentId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }
}