package com.project.paymentservice.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.paymentservice.support.AbstractIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class PaymentSchemaIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private final UUID from = UUID.randomUUID();
    private final UUID to = UUID.randomUUID();

    @Test
    void aNewPaymentIsPendingWithNoAttempts() {
        UUID id = insertPayment("key-" + UUID.randomUUID());

        Map<String, Object> row = jdbc.queryForMap("SELECT status, attempts FROM payments WHERE id = ?", id);

        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(0);
    }

    @Test
    void nonsenseRequestsAreRefused() {
        assertThatThrownBy(() -> insert("key-" + UUID.randomUUID(), from, to, 0, "KZT"))
                .hasStackTraceContaining("amount_minor");
        assertThatThrownBy(() -> insert("key-" + UUID.randomUUID(), from, from, 5, "KZT"))
                .hasStackTraceContaining("ck_payment_accounts_differ");
        assertThatThrownBy(() -> insert("key-" + UUID.randomUUID(), from, to, 5, "kzt"))
                .hasStackTraceContaining("currency");
    }

    @Test
    void oneKeyOnePayment() {
        String key = "key-" + UUID.randomUUID();
        insertPayment(key);

        assertThatThrownBy(() -> insertPayment(key)).hasStackTraceContaining("idempotency_key");
    }


    /** The database and PaymentStatus.canMoveTo must agree about every move, in both capture modes. */
    @Test
    void aPaymentMovesOnlyAlongTheStateMachine() {
        for (CaptureMode mode : CaptureMode.values()) {
            for (PaymentStatus source : PaymentStatus.values()) {
                if (!source.isPossibleFor(mode)) {
                    continue;
                }
                for (PaymentStatus target : PaymentStatus.values()) {
                    if (source == target) {
                        continue;
                    }
                    assertThat(moveSucceeds(mode, source, target))
                            .as(mode + ": " + source + " -> " + target)
                            .isEqualTo(source.canMoveTo(target, mode));
                }
            }
        }
    }

    @Test
    void aTwoStepPaymentIsAuthorizedWithItsHoldAndItsExpiryTogether() {
        UUID id = insert("key-" + UUID.randomUUID(), from, to, 100, "KZT", CaptureMode.MANUAL);

        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET status = 'AUTHORIZED' WHERE id = ?", id))
                .hasStackTraceContaining("ck_payment_manual_has_hold");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payments SET ledger_hold_id = ? WHERE id = ?", UUID.randomUUID(), id))
                .hasStackTraceContaining("ck_payment_hold_has_expiry");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payments SET status = 'COMPLETED', ledger_transaction_id = ? WHERE id = ?",
                UUID.randomUUID(), id))
                .hasStackTraceContaining("invalid_payment_transition");
    }

    @Test
    void onlyATwoStepPaymentHasAHoldOrIsAuthorized() {
        UUID id = insert("key-" + UUID.randomUUID(), from, to, 100, "KZT", CaptureMode.AUTOMATIC);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payments SET ledger_hold_id = ?, authorization_expires_at = now() WHERE id = ?",
                UUID.randomUUID(), id))
                .hasStackTraceContaining("ck_payment_hold_only_if_manual");
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET status = 'AUTHORIZED' WHERE id = ?", id))
                .hasStackTraceContaining("invalid_payment_transition");
    }

    @Test
    void theModeAndTheHoldNeverChange() {
        UUID id = paymentIn(CaptureMode.MANUAL, PaymentStatus.AUTHORIZED);

        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET capture_mode = 'AUTOMATIC' WHERE id = ?", id))
                .hasStackTraceContaining("payment_immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET ledger_hold_id = ? WHERE id = ?", UUID.randomUUID(), id))
                .hasStackTraceContaining("payment_hold_immutable");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payments SET authorization_expires_at = now() + interval '9 days' WHERE id = ?", id))
                .hasStackTraceContaining("payment_hold_immutable");
    }

    @Test
    void aLedgerHoldBelongsToOnePayment() {
        UUID hold = UUID.randomUUID();
        UUID first = insert("key-" + UUID.randomUUID(), from, to, 100, "KZT", CaptureMode.MANUAL);
        UUID second = insert("key-" + UUID.randomUUID(), from, to, 100, "KZT", CaptureMode.MANUAL);
        jdbc.update("UPDATE payments SET status = 'AUTHORIZED', ledger_hold_id = ?, "
                + "authorization_expires_at = now() + interval '1 hour' WHERE id = ?", hold, first);

        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET status = 'AUTHORIZED', ledger_hold_id = ?, "
                + "authorization_expires_at = now() + interval '1 hour' WHERE id = ?", hold, second))
                .hasStackTraceContaining("uq_payments_ledger_hold");
    }

    private boolean moveSucceeds(CaptureMode mode, PaymentStatus source, PaymentStatus target) {
        UUID id = paymentIn(mode, source);
        try {
            switch (target) {
                case PENDING -> jdbc.update("UPDATE payments SET status = 'PENDING', "
                        + "ledger_transaction_id = NULL, failure_code = NULL WHERE id = ?", id);
                case AUTHORIZED -> jdbc.update("UPDATE payments SET status = 'AUTHORIZED', "
                        + "ledger_hold_id = COALESCE(ledger_hold_id, ?), "
                        + "authorization_expires_at = COALESCE(authorization_expires_at, now() + interval '1 hour'), "
                        + "ledger_transaction_id = NULL, failure_code = NULL WHERE id = ?", UUID.randomUUID(), id);
                case COMPLETED -> jdbc.update("UPDATE payments SET status = 'COMPLETED', "
                        + "ledger_transaction_id = COALESCE(ledger_transaction_id, ?), failure_code = NULL "
                        + "WHERE id = ?", UUID.randomUUID(), id);
                case FAILED -> jdbc.update("UPDATE payments SET status = 'FAILED', "
                        + "failure_code = 'test', ledger_transaction_id = NULL WHERE id = ?", id);
                case CANCELLED, EXPIRED -> jdbc.update("UPDATE payments SET status = ?, "
                        + "ledger_transaction_id = NULL, failure_code = NULL WHERE id = ?", target.name(), id);
                case REVERSED -> jdbc.update("UPDATE payments SET status = 'REVERSED', "
                        + "ledger_transaction_id = COALESCE(ledger_transaction_id, ?), failure_code = NULL "
                        + "WHERE id = ?", UUID.randomUUID(), id);
            }
            return true;
        } catch (DataAccessException e) {
            return false;
        }
    }

    private UUID paymentIn(CaptureMode mode, PaymentStatus status) {
        UUID id = insert("key-" + UUID.randomUUID(), from, to, 100, "KZT", mode);
        switch (status) {
            case PENDING -> {
            }
            case FAILED -> jdbc.update("UPDATE payments SET status = 'FAILED', failure_code = 'seed' WHERE id = ?", id);
            case AUTHORIZED -> authorize(id);
            case CANCELLED, EXPIRED -> {
                authorize(id);
                jdbc.update("UPDATE payments SET status = ? WHERE id = ?", status.name(), id);
            }
            case COMPLETED -> complete(id, mode);
            case REVERSED -> {
                complete(id, mode);
                jdbc.update("UPDATE payments SET status = 'REVERSED' WHERE id = ?", id);
            }
        }
        return id;
    }

    private void authorize(UUID id) {
        jdbc.update("UPDATE payments SET status = 'AUTHORIZED', ledger_hold_id = ?, "
                + "authorization_expires_at = now() + interval '1 hour' WHERE id = ?", UUID.randomUUID(), id);
    }

    private void complete(UUID id, CaptureMode mode) {
        if (mode == CaptureMode.MANUAL) {
            authorize(id);
        }
        jdbc.update("UPDATE payments SET status = 'COMPLETED', ledger_transaction_id = ? WHERE id = ?",
                UUID.randomUUID(), id);
    }

    private UUID insert(String key, UUID fromAccount, UUID toAccount, long amount, String currency, CaptureMode mode) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payments (id, idempotency_key, request_hash, from_account_id, to_account_id, "
                        + "amount_minor, currency, capture_mode) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, key, "a".repeat(64), fromAccount, toAccount, amount, currency, mode.name());
        return id;
    }

    @Test
    void aCompletedPaymentNeedsALedgerTransactionAndAFailedOneNeedsACode() {
        UUID id = insertPayment("key-" + UUID.randomUUID());

        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET status = 'COMPLETED' WHERE id = ?", id))
                .hasStackTraceContaining("ck_payment_done_has_transaction");
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET status = 'FAILED' WHERE id = ?", id))
                .hasStackTraceContaining("ck_payment_failed_has_code");
    }

    @Test
    void theRequestCannotBeChangedAndPaymentsAreNeverDeleted() {
        UUID id = insertPayment("key-" + UUID.randomUUID());

        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET amount_minor = 999 WHERE id = ?", id))
                .hasStackTraceContaining("payment_immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET to_account_id = ? WHERE id = ?", UUID.randomUUID(), id))
                .hasStackTraceContaining("payment_immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM payments WHERE id = ?", id))
                .hasStackTraceContaining("payment_not_deletable");
    }

    @Test
    void aLedgerTransactionBelongsToOnePayment() {
        UUID ledgerTransaction = UUID.randomUUID();
        UUID first = insertPayment("key-" + UUID.randomUUID());
        UUID second = insertPayment("key-" + UUID.randomUUID());
        jdbc.update("UPDATE payments SET status = 'COMPLETED', ledger_transaction_id = ? WHERE id = ?",
                ledgerTransaction, first);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payments SET status = 'COMPLETED', ledger_transaction_id = ? WHERE id = ?",
                ledgerTransaction, second))
                .hasStackTraceContaining("uq_payments_ledger_transaction");
    }

    private boolean moveSucceeds(PaymentStatus source, PaymentStatus target) {
        UUID id = paymentIn(source);
        try {
            switch (target) {
                case PENDING -> jdbc.update("UPDATE payments SET status = 'PENDING', "
                        + "ledger_transaction_id = NULL, failure_code = NULL WHERE id = ?", id);
                case COMPLETED -> jdbc.update("UPDATE payments SET status = 'COMPLETED', "
                        + "ledger_transaction_id = ?, failure_code = NULL WHERE id = ?", UUID.randomUUID(), id);
                case FAILED -> jdbc.update("UPDATE payments SET status = 'FAILED', "
                        + "failure_code = 'test', ledger_transaction_id = NULL WHERE id = ?", id);
                case REVERSED -> jdbc.update("UPDATE payments SET status = 'REVERSED', "
                        + "ledger_transaction_id = COALESCE(ledger_transaction_id, ?), failure_code = NULL "
                        + "WHERE id = ?", UUID.randomUUID(), id);
            }
            return true;
        } catch (DataAccessException e) {
            return false;
        }
    }

    private UUID paymentIn(PaymentStatus status) {
        UUID id = insertPayment("key-" + UUID.randomUUID());
        switch (status) {
            case PENDING -> {
            }
            case COMPLETED -> complete(id);
            case FAILED -> jdbc.update("UPDATE payments SET status = 'FAILED', failure_code = 'seed' WHERE id = ?", id);
            case REVERSED -> {
                complete(id);
                jdbc.update("UPDATE payments SET status = 'REVERSED' WHERE id = ?", id);
            }
        }
        return id;
    }

    private void complete(UUID id) {
        jdbc.update("UPDATE payments SET status = 'COMPLETED', ledger_transaction_id = ? WHERE id = ?",
                UUID.randomUUID(), id);
    }

    private UUID insertPayment(String key) {
        return insert(key, from, to, 100, "KZT");
    }

    private UUID insert(String key, UUID fromAccount, UUID toAccount, long amount, String currency) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payments (id, idempotency_key, request_hash, from_account_id, to_account_id, "
                        + "amount_minor, currency) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, key, "a".repeat(64), fromAccount, toAccount, amount, currency);
        return id;
    }
}