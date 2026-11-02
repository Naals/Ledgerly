package com.project.paymentservice.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void aReversalEventReversesTheCompletedPaymentNamedByItsReference() {
        UUID paymentId = completedPayment();

        Outcome outcome = handler.handle(UUID.randomUUID(), reversalOf(paymentId));

        assertThat(outcome).isEqualTo(Outcome.PAYMENT_REVERSED);
        assertThat(statusOf(paymentId)).isEqualTo("REVERSED");
    }

    @Test
    void deliveringTheSameEventAgainChangesNothing() {
        UUID paymentId = completedPayment();
        UUID eventId = UUID.randomUUID();
        handler.handle(eventId, reversalOf(paymentId));

        Outcome again = handler.handle(eventId, reversalOf(paymentId));

        assertThat(again).isEqualTo(Outcome.ALREADY_PROCESSED);
        assertThat(statusOf(paymentId)).isEqualTo("REVERSED");
    }

    @Test
    void aSecondReversalEventForTheSamePaymentIsHarmless() {
        UUID paymentId = completedPayment();
        handler.handle(UUID.randomUUID(), reversalOf(paymentId));

        Outcome second = handler.handle(UUID.randomUUID(), reversalOf(paymentId));

        assertThat(second).isEqualTo(Outcome.PAYMENT_ALREADY_REVERSED);
        assertThat(statusOf(paymentId)).isEqualTo("REVERSED");
    }

    @Test
    void aReversalOfAPaymentWeDoNotKnowIsRecordedButChangesNoPayment() {
        UUID paymentId = completedPayment();

        Outcome outcome = handler.handle(UUID.randomUUID(), reversalOf(UUID.randomUUID()));

        assertThat(outcome).isEqualTo(Outcome.NOT_OUR_PAYMENT);
        assertThat(statusOf(paymentId)).isEqualTo("COMPLETED");
    }

    @Test
    void aReferenceThatIsNotAPaymentIdIsNotOurs() {
        UUID paymentId = completedPayment();

        Outcome outcome = handler.handle(UUID.randomUUID(), reversalWithReference("invoice-42"));

        assertThat(outcome).isEqualTo(Outcome.NOT_OUR_PAYMENT);
        assertThat(statusOf(paymentId)).isEqualTo("COMPLETED");
    }

    @Test
    void aReversalThatArrivesBeforeThePaymentCompletesWaitsAndSucceedsOnceItHas() {
        UUID paymentId = pendingPayment();
        UUID eventId = UUID.randomUUID();
        Long before = jdbc.queryForObject("SELECT count(*) FROM processed_events", Long.class);

        assertThatThrownBy(() -> handler.handle(eventId, reversalOf(paymentId)))
                .isInstanceOf(PaymentNotSettledException.class);

        // nothing was decided and nothing was remembered, so the event can come again
        assertThat(statusOf(paymentId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Long.class)).isEqualTo(before);

        // the payment catches up with the ledger ...
        jdbc.update("UPDATE payments SET status = 'COMPLETED', ledger_transaction_id = ? WHERE id = ?",
                UUID.randomUUID(), paymentId);

        // ... and the SAME event now reverses it
        assertThat(handler.handle(eventId, reversalOf(paymentId))).isEqualTo(Outcome.PAYMENT_REVERSED);
        assertThat(statusOf(paymentId)).isEqualTo("REVERSED");
    }

    @Test
    void eventsThatAreNotReversalsAreIgnoredAndNotRecorded() {
        Long before = jdbc.queryForObject("SELECT count(*) FROM processed_events", Long.class);

        Outcome outcome = handler.handle(UUID.randomUUID(), """
                {"version": 1, "transactionId": "%s", "type": "TRANSFER", "reference": null, "reversesTransactionId": null}
                """.formatted(UUID.randomUUID()));

        assertThat(outcome).isEqualTo(Outcome.NOT_A_REVERSAL);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Long.class)).isEqualTo(before);
    }

    /** A reversal event whose reference names the payment, as the ledger sends it. */
    static String reversalOf(UUID paymentId) {
        return reversalWithReference(paymentId.toString());
    }

    static String reversalWithReference(String reference) {
        return """
                {"version": 1, "transactionId": "%s", "type": "REVERSAL", "reference": "%s", "reversesTransactionId": "%s"}
                """.formatted(UUID.randomUUID(), reference, UUID.randomUUID());
    }

    private UUID pendingPayment() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payments (id, idempotency_key, request_hash, from_account_id, to_account_id, "
                        + "amount_minor, currency) VALUES (?, ?, ?, ?, ?, 100, 'KZT')",
                id, "key-" + UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), UUID.randomUUID());
        return id;
    }

    private UUID completedPayment() {
        UUID id = pendingPayment();
        jdbc.update("UPDATE payments SET status = 'COMPLETED', ledger_transaction_id = ? WHERE id = ?",
                UUID.randomUUID(), id);
        return id;
    }

    private String statusOf(UUID paymentId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }
}