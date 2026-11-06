package com.project.paymentservice.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.paymentservice.events.PaymentHoldEventHandler.Outcome;
import com.project.paymentservice.ledger.HoldState;
import com.project.paymentservice.payment.PaymentStatus;
import com.project.paymentservice.support.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PaymentHoldEventHandlerIT extends AbstractIntegrationTest {

    /** The intended behaviour, cell by cell. Written out, not derived from the code under test. */
    private record Cell(HoldState hold, PaymentStatus payment, Outcome outcome, PaymentStatus resulting) {
    }

    private record Seeded(UUID id, UUID holdId) {
    }

    private static final List<Cell> TABLE = List.of(
            new Cell(HoldState.CAPTURED, PaymentStatus.AUTHORIZED, Outcome.PAYMENT_COMPLETED, PaymentStatus.COMPLETED),
            new Cell(HoldState.CAPTURED, PaymentStatus.COMPLETED, Outcome.ALREADY_APPLIED, PaymentStatus.COMPLETED),
            new Cell(HoldState.CAPTURED, PaymentStatus.REVERSED, Outcome.ALREADY_APPLIED, PaymentStatus.REVERSED),
            new Cell(HoldState.CAPTURED, PaymentStatus.FAILED, Outcome.PAYMENT_INCONSISTENT, PaymentStatus.FAILED),
            new Cell(HoldState.CAPTURED, PaymentStatus.CANCELLED, Outcome.PAYMENT_INCONSISTENT, PaymentStatus.CANCELLED),
            new Cell(HoldState.CAPTURED, PaymentStatus.EXPIRED, Outcome.PAYMENT_INCONSISTENT, PaymentStatus.EXPIRED),
            new Cell(HoldState.CAPTURED, PaymentStatus.PENDING, null, PaymentStatus.PENDING),
            new Cell(HoldState.RELEASED, PaymentStatus.AUTHORIZED, Outcome.PAYMENT_CANCELLED, PaymentStatus.CANCELLED),
            new Cell(HoldState.RELEASED, PaymentStatus.CANCELLED, Outcome.ALREADY_APPLIED, PaymentStatus.CANCELLED),
            new Cell(HoldState.RELEASED, PaymentStatus.EXPIRED, Outcome.ALREADY_APPLIED, PaymentStatus.EXPIRED),
            new Cell(HoldState.RELEASED, PaymentStatus.FAILED, Outcome.ALREADY_APPLIED, PaymentStatus.FAILED),
            new Cell(HoldState.RELEASED, PaymentStatus.COMPLETED, Outcome.PAYMENT_INCONSISTENT, PaymentStatus.COMPLETED),
            new Cell(HoldState.RELEASED, PaymentStatus.REVERSED, Outcome.PAYMENT_INCONSISTENT, PaymentStatus.REVERSED),
            new Cell(HoldState.RELEASED, PaymentStatus.PENDING, null, PaymentStatus.PENDING),
            new Cell(HoldState.EXPIRED, PaymentStatus.AUTHORIZED, Outcome.PAYMENT_EXPIRED, PaymentStatus.EXPIRED),
            new Cell(HoldState.EXPIRED, PaymentStatus.CANCELLED, Outcome.ALREADY_APPLIED, PaymentStatus.CANCELLED),
            new Cell(HoldState.EXPIRED, PaymentStatus.EXPIRED, Outcome.ALREADY_APPLIED, PaymentStatus.EXPIRED),
            new Cell(HoldState.EXPIRED, PaymentStatus.FAILED, Outcome.ALREADY_APPLIED, PaymentStatus.FAILED),
            new Cell(HoldState.EXPIRED, PaymentStatus.COMPLETED, Outcome.PAYMENT_INCONSISTENT, PaymentStatus.COMPLETED),
            new Cell(HoldState.EXPIRED, PaymentStatus.REVERSED, Outcome.PAYMENT_INCONSISTENT, PaymentStatus.REVERSED),
            new Cell(HoldState.EXPIRED, PaymentStatus.PENDING, null, PaymentStatus.PENDING));

    @Autowired
    PaymentHoldEventHandler handler;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry registry;

    @Test
    void theTableCoversEveryCombinationOnce() {
        for (HoldState hold : List.of(HoldState.CAPTURED, HoldState.RELEASED, HoldState.EXPIRED)) {
            for (PaymentStatus status : PaymentStatus.values()) {
                assertThat(TABLE.stream().filter(cell -> cell.hold() == hold && cell.payment() == status).count())
                        .as(hold + " / " + status).isEqualTo(1);
            }
        }
    }

    @Test
    void everyHoldOutcomeAgainstEveryPaymentStatusDoesWhatTheTableSays() {
        for (Cell cell : TABLE) {
            Seeded payment = seed(cell.payment());
            UUID transaction = UUID.randomUUID();
            String payload = event(cell.hold(), payment.holdId(), payment.id().toString(),
                    cell.hold() == HoldState.CAPTURED ? transaction : null);
            double before = inconsistencies(cell.payment());

            if (cell.outcome() == null) {
                assertThatThrownBy(() -> handler.handle(UUID.randomUUID(), payload)).as(cell.toString())
                        .isInstanceOf(PaymentNotSettledException.class);
            } else {
                assertThat(handler.handle(UUID.randomUUID(), payload)).as(cell.toString()).isEqualTo(cell.outcome());
            }

            assertThat(statusOf(payment.id())).as(cell.toString()).isEqualTo(cell.resulting().name());
            if (cell.outcome() == Outcome.PAYMENT_COMPLETED) {
                assertThat(jdbc.queryForObject("SELECT ledger_transaction_id FROM payments WHERE id = ?",
                        UUID.class, payment.id())).as(cell.toString()).isEqualTo(transaction);
            }
            double raised = inconsistencies(cell.payment()) - before;
            assertThat(raised).as(cell + " raised").isEqualTo(cell.outcome() == Outcome.PAYMENT_INCONSISTENT ? 1.0 : 0.0);
        }
    }

    @Test
    void theEchoOfAPaymentsOwnCaptureChangesNothing() {
        Seeded payment = seed(PaymentStatus.COMPLETED);

        assertThat(handler.handle(UUID.randomUUID(),
                event(HoldState.CAPTURED, payment.holdId(), payment.id().toString(), UUID.randomUUID())))
                .isEqualTo(Outcome.ALREADY_APPLIED);
    }

    @Test
    void deliveringTheSameEventAgainChangesNothing() {
        Seeded payment = seed(PaymentStatus.AUTHORIZED);
        UUID eventId = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();
        String payload = event(HoldState.CAPTURED, payment.holdId(), payment.id().toString(), transaction);
        assertThat(handler.handle(eventId, payload)).isEqualTo(Outcome.PAYMENT_COMPLETED);

        assertThat(handler.handle(eventId, payload)).isEqualTo(Outcome.ALREADY_PROCESSED);
        assertThat(statusOf(payment.id())).isEqualTo("COMPLETED");
    }

    @Test
    void aHoldThatIsNotOneOfOursIsNobodysBusinessAndIsNotRemembered() {
        Seeded authorized = seed(PaymentStatus.AUTHORIZED);
        UUID oneStep = UUID.randomUUID();
        jdbc.update("INSERT INTO payments (id, idempotency_key, request_hash, from_account_id, to_account_id, "
                        + "amount_minor, currency) VALUES (?, ?, ?, ?, ?, 100, 'KZT')",
                oneStep, "key-" + UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), UUID.randomUUID());
        long before = processedRows();

        assertThat(handler.handle(UUID.randomUUID(), event(HoldState.RELEASED, UUID.randomUUID(), null, null)))
                .isEqualTo(Outcome.NOT_OUR_HOLD);
        assertThat(handler.handle(UUID.randomUUID(), event(HoldState.RELEASED, UUID.randomUUID(), "invoice-42", null)))
                .isEqualTo(Outcome.NOT_OUR_HOLD);
        assertThat(handler.handle(UUID.randomUUID(), event(HoldState.RELEASED, UUID.randomUUID(), UUID.randomUUID().toString(), null)))
                .isEqualTo(Outcome.NOT_OUR_HOLD);                                   // a payment we do not know
        assertThat(handler.handle(UUID.randomUUID(), event(HoldState.RELEASED, UUID.randomUUID(), oneStep.toString(), null)))
                .isEqualTo(Outcome.NOT_OUR_HOLD);                                   // a one-step payment has no hold
        assertThat(handler.handle(UUID.randomUUID(), event(HoldState.RELEASED, UUID.randomUUID(), authorized.id().toString(), null)))
                .isEqualTo(Outcome.NOT_OUR_HOLD);                                   // the payment's hold is a different one

        assertThat(processedRows()).isEqualTo(before);
        assertThat(statusOf(authorized.id())).isEqualTo("AUTHORIZED");
    }

    @Test
    void anActiveHoldTellsUsNothing() {
        Seeded payment = seed(PaymentStatus.AUTHORIZED);
        long before = processedRows();

        assertThat(handler.handle(UUID.randomUUID(), event(HoldState.ACTIVE, payment.holdId(), payment.id().toString(), null)))
                .isEqualTo(Outcome.IGNORED);

        assertThat(processedRows()).isEqualTo(before);
    }

    @Test
    void anOutcomeThatArrivesBeforeTheAuthorizationIsRecordedWaitsAndThenSucceeds() {
        Seeded pending = seed(PaymentStatus.PENDING);
        UUID hold = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();
        String payload = event(HoldState.CAPTURED, hold, pending.id().toString(), transaction);
        long before = processedRows();

        assertThatThrownBy(() -> handler.handle(eventId, payload)).isInstanceOf(PaymentNotSettledException.class);

        // nothing was decided and nothing was remembered, so the event can come again
        assertThat(statusOf(pending.id())).isEqualTo("PENDING");
        assertThat(processedRows()).isEqualTo(before);

        jdbc.update("UPDATE payments SET status = 'AUTHORIZED', ledger_hold_id = ?, "
                + "authorization_expires_at = now() + interval '1 hour' WHERE id = ?", hold, pending.id());   // the service catches up ...

        assertThat(handler.handle(eventId, payload)).isEqualTo(Outcome.PAYMENT_COMPLETED);                  // ... and the SAME event works
    }

    @Test
    void anEventWeCannotReadIsNotSwallowed() {
        assertThatThrownBy(() -> handler.handle(UUID.randomUUID(), "this is not json"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler.handle(UUID.randomUUID(),
                event(HoldState.CAPTURED, UUID.randomUUID(), UUID.randomUUID().toString(), null)))
                .isInstanceOf(IllegalArgumentException.class);   // a capture that names no transaction
    }

    static String event(HoldState state, UUID holdId, String reference, UUID transaction) {
        return """
                {"version": 1, "holdId": "%s", "status": "%s", "previousStatus": "ACTIVE", "reference": %s,
                 "ledgerTransactionId": %s}
                """.formatted(holdId, state, reference == null ? "null" : "\"" + reference + "\"",
                transaction == null ? "null" : "\"" + transaction + "\"");
    }

    /** A manual payment that has reached the given status, by the moves the database allows. */
    private Seeded seed(PaymentStatus status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payments (id, idempotency_key, request_hash, from_account_id, to_account_id, "
                        + "amount_minor, currency, capture_mode) VALUES (?, ?, ?, ?, ?, 100, 'KZT', 'MANUAL')",
                id, "key-" + UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), UUID.randomUUID());
        if (status == PaymentStatus.PENDING) {
            return new Seeded(id, UUID.randomUUID());
        }
        if (status == PaymentStatus.FAILED) {
            jdbc.update("UPDATE payments SET status = 'FAILED', failure_code = 'seed' WHERE id = ?", id);
            return new Seeded(id, UUID.randomUUID());
        }
        UUID hold = UUID.randomUUID();
        jdbc.update("UPDATE payments SET status = 'AUTHORIZED', ledger_hold_id = ?, "
                + "authorization_expires_at = now() + interval '1 hour' WHERE id = ?", hold, id);
        if (status == PaymentStatus.CANCELLED || status == PaymentStatus.EXPIRED) {
            jdbc.update("UPDATE payments SET status = ? WHERE id = ?", status.name(), id);
        }
        if (status == PaymentStatus.COMPLETED || status == PaymentStatus.REVERSED) {
            jdbc.update("UPDATE payments SET status = 'COMPLETED', ledger_transaction_id = ? WHERE id = ?",
                    UUID.randomUUID(), id);
        }
        if (status == PaymentStatus.REVERSED) {
            jdbc.update("UPDATE payments SET status = 'REVERSED' WHERE id = ?", id);
        }
        return new Seeded(id, hold);
    }

    private String statusOf(UUID paymentId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }

    private long processedRows() {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE consumer = ?", Long.class,
                PaymentHoldEventHandler.CONSUMER);
    }

    private double inconsistencies(PaymentStatus paymentStatus) {
        return registry.counter("payments.events.inconsistent", "payment_status", paymentStatus.name()).count();
    }
}