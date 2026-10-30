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

    /** The database and PaymentStatus.canMoveTo must agree about every pair of statuses. */
    @Test
    void aPaymentMovesOnlyAlongTheStateMachine() {
        for (PaymentStatus source : PaymentStatus.values()) {
            for (PaymentStatus target : PaymentStatus.values()) {
                if (source == target) {
                    continue;
                }
                boolean databaseAllowsIt = moveSucceeds(source, target);

                assertThat(databaseAllowsIt)
                        .as(source + " -> " + target)
                        .isEqualTo(source.canMoveTo(target));
            }
        }
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