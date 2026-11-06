package com.project.paymentservice.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.paymentservice.events.PaymentCaptureHandler.Outcome;
import com.project.paymentservice.support.AbstractIntegrationTest;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PaymentCaptureHandlerIT extends AbstractIntegrationTest {

    @Autowired
    PaymentCaptureHandler handler;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry registry;

    @Test
    void aCaptureEventCompletesTheAuthorizedPaymentNamedByItsReference() {
        UUID paymentId = manualPayment(true);
        UUID transaction = UUID.randomUUID();

        Outcome outcome = handler.handle(UUID.randomUUID(), transferOf(paymentId.toString(), transaction));

        assertThat(outcome).isEqualTo(Outcome.PAYMENT_COMPLETED);
        assertThat(statusOf(paymentId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT ledger_transaction_id FROM payments WHERE id = ?", UUID.class, paymentId))
                .isEqualTo(transaction);
    }

    @Test
    void theSameEventAgainChangesNothing() {
        UUID paymentId = manualPayment(true);
        UUID eventId = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();
        handler.handle(eventId, transferOf(paymentId.toString(), transaction));

        assertThat(handler.handle(eventId, transferOf(paymentId.toString(), transaction)))
                .isEqualTo(Outcome.ALREADY_PROCESSED);
        assertThat(statusOf(paymentId)).isEqualTo("COMPLETED");
    }

    @Test
    void anOrdinaryTransferIsNobodysBusinessAndIsNotRemembered() {
        long before = processedRows();

        assertThat(handler.handle(UUID.randomUUID(), transferOf(null, UUID.randomUUID()))).isEqualTo(Outcome.NOT_A_CAPTURE);
        assertThat(handler.handle(UUID.randomUUID(), transferOf(UUID.randomUUID().toString(), UUID.randomUUID())))
                .isEqualTo(Outcome.NOT_A_CAPTURE);
        assertThat(handler.handle(UUID.randomUUID(), transferOf("invoice-42", UUID.randomUUID())))
                .isEqualTo(Outcome.NOT_A_CAPTURE);

        assertThat(processedRows()).isEqualTo(before);
    }

    @Test
    void theTransferOfAOneStepPaymentIsNotACapture() {
        UUID id = UUID.randomUUID();
        insert(id, "AUTOMATIC");
        long before = processedRows();

        assertThat(handler.handle(UUID.randomUUID(), transferOf(id.toString(), UUID.randomUUID())))
                .isEqualTo(Outcome.NOT_A_CAPTURE);
        assertThat(processedRows()).isEqualTo(before);
    }

    @Test
    void eventsThatAreNotTransfersAreIgnored() {
        assertThat(handler.handle(UUID.randomUUID(),
                "{\"version\": 1, \"transactionId\": \"" + UUID.randomUUID() + "\", \"type\": \"DEPOSIT\"}"))
                .isEqualTo(Outcome.NOT_A_TRANSFER);
    }

    @Test
    void aCaptureThatArrivesBeforeTheAuthorizationIsRecordedWaitsAndThenSucceeds() {
        UUID paymentId = manualPayment(false);   // the ledger is ahead: the payment is still PENDING
        UUID eventId = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();
        long before = processedRows();

        assertThatThrownBy(() -> handler.handle(eventId, transferOf(paymentId.toString(), transaction)))
                .isInstanceOf(PaymentNotSettledException.class);

        // nothing was decided and nothing was remembered, so the event can come again
        assertThat(statusOf(paymentId)).isEqualTo("PENDING");
        assertThat(processedRows()).isEqualTo(before);

        authorize(paymentId);   // the service catches up ...

        assertThat(handler.handle(eventId, transferOf(paymentId.toString(), transaction)))   // ... and the SAME event works
                .isEqualTo(Outcome.PAYMENT_COMPLETED);
    }

    @Test
    void aTransferForACancelledPaymentIsRaisedAsInconsistentNotHidden() {
        UUID paymentId = manualPayment(true);
        jdbc.update("UPDATE payments SET status = 'CANCELLED' WHERE id = ?", paymentId);
        double before = inconsistencies("CANCELLED");

        assertThat(handler.handle(UUID.randomUUID(), transferOf(paymentId.toString(), UUID.randomUUID())))
                .isEqualTo(Outcome.PAYMENT_INCONSISTENT);

        assertThat(statusOf(paymentId)).isEqualTo("CANCELLED");
        assertThat(inconsistencies("CANCELLED")).isEqualTo(before + 1);
    }

    private double inconsistencies(String paymentStatus) {
        return registry.counter("payments.events.inconsistent", "payment_status", paymentStatus).count();
    }

    @Test
    void aCaptureEventForAPaymentAlreadyCompletedByItsOwnAnswerIsHarmless() {
        UUID paymentId = manualPayment(true);
        jdbc.update("UPDATE payments SET status = 'COMPLETED', ledger_transaction_id = ? WHERE id = ?",
                UUID.randomUUID(), paymentId);

        assertThat(handler.handle(UUID.randomUUID(), transferOf(paymentId.toString(), UUID.randomUUID())))
                .isEqualTo(Outcome.PAYMENT_ALREADY_COMPLETED);
    }

    static String transferOf(String reference, UUID transactionId) {
        return """
                {"version": 1, "transactionId": "%s", "type": "TRANSFER", "reference": %s, "reversesTransactionId": null}
                """.formatted(transactionId, reference == null ? "null" : "\"" + reference + "\"");
    }

    private UUID manualPayment(boolean authorized) {
        UUID id = UUID.randomUUID();
        insert(id, "MANUAL");
        if (authorized) {
            authorize(id);
        }
        return id;
    }

    private void insert(UUID id, String mode) {
        jdbc.update("INSERT INTO payments (id, idempotency_key, request_hash, from_account_id, to_account_id, "
                        + "amount_minor, currency, capture_mode) VALUES (?, ?, ?, ?, ?, 100, 'KZT', ?)",
                id, "key-" + UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), UUID.randomUUID(), mode);
    }

    private void authorize(UUID id) {
        jdbc.update("UPDATE payments SET status = 'AUTHORIZED', ledger_hold_id = ?, "
                + "authorization_expires_at = now() + interval '1 hour' WHERE id = ?", UUID.randomUUID(), id);
    }

    private String statusOf(UUID paymentId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }

    private long processedRows() {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE consumer = ?", Long.class,
                PaymentCaptureHandler.CONSUMER);
    }
}