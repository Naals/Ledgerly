package com.project.paymentservice.payment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL only. Every update is guarded by the status the payment must be in, so if two instances (or a capture and a
 * cancel) answer the same payment at once, exactly one wins and the other changes nothing.
 */
@Repository
public class PaymentRepository {

    private static final String COLUMNS = "id, idempotency_key, request_hash, from_account_id, to_account_id, "
            + "amount_minor, currency, capture_mode, status, failure_code, ledger_transaction_id, ledger_hold_id, "
            + "authorization_expires_at, attempts, created_at, updated_at";

    private static final RowMapper<Payment> ROW_MAPPER = (rs, rowNum) -> new Payment(
            rs.getObject("id", UUID.class),
            rs.getString("idempotency_key"),
            rs.getString("request_hash"),
            rs.getObject("from_account_id", UUID.class),
            rs.getObject("to_account_id", UUID.class),
            rs.getLong("amount_minor"),
            rs.getString("currency"),
            CaptureMode.valueOf(rs.getString("capture_mode")),
            PaymentStatus.valueOf(rs.getString("status")),
            rs.getString("failure_code"),
            rs.getObject("ledger_transaction_id", UUID.class),
            rs.getObject("ledger_hold_id", UUID.class),
            rs.getObject("authorization_expires_at", OffsetDateTime.class),
            rs.getInt("attempts"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the payment unless the idempotency key was used before. Returns the new id if THIS call created
     * the payment. If another request is creating the same key right now, this waits for it and returns empty.
     */
    public Optional<UUID> insertIfNew(String idempotencyKey, String requestHash, UUID fromAccountId,
                                      UUID toAccountId, long amountMinor, String currency, CaptureMode captureMode) {
        return jdbc.sql("INSERT INTO payments (idempotency_key, request_hash, from_account_id, to_account_id, "
                        + "amount_minor, currency, capture_mode) "
                        + "VALUES (:key, :hash, :fromAccount, :toAccount, :amount, :currency, :mode) "
                        + "ON CONFLICT (idempotency_key) DO NOTHING RETURNING id")
                .param("key", idempotencyKey)
                .param("hash", requestHash)
                .param("fromAccount", fromAccountId)
                .param("toAccount", toAccountId)
                .param("amount", amountMinor)
                .param("currency", currency)
                .param("mode", captureMode.name())
                .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                .optional();
    }

    public Optional<Payment> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payments WHERE id = :id")
                .param("id", id)
                .query(ROW_MAPPER)
                .optional();
    }

    public Optional<Payment> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payments WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query(ROW_MAPPER)
                .optional();
    }

    /** One-step payments: records the ledger's success. False if the payment was no longer pending. */
    public boolean markCompleted(UUID id, UUID ledgerTransactionId) {
        return jdbc.sql("UPDATE payments SET status = 'COMPLETED', ledger_transaction_id = :transactionId "
                        + "WHERE id = :id AND status = 'PENDING'")
                .param("transactionId", ledgerTransactionId)
                .param("id", id)
                .update() == 1;
    }

    /** Records the ledger's final refusal. False if the payment was no longer pending. */
    public boolean markFailed(UUID id, String failureCode) {
        return jdbc.sql("UPDATE payments SET status = 'FAILED', failure_code = :code "
                        + "WHERE id = :id AND status = 'PENDING'")
                .param("code", failureCode)
                .param("id", id)
                .update() == 1;
    }

    /** Counts an attempt the ledger could not answer. This also pushes the payment's next retry back. */
    public void recordAttempt(UUID id) {
        jdbc.sql("UPDATE payments SET attempts = attempts + 1 WHERE id = :id AND status = 'PENDING'")
                .param("id", id)
                .update();
    }

    /** Two-step payments, step one: the ledger holds the money. False if the payment was no longer pending. */
    public boolean markAuthorized(UUID id, UUID ledgerHoldId, OffsetDateTime authorizationExpiresAt) {
        return jdbc.sql("UPDATE payments SET status = 'AUTHORIZED', ledger_hold_id = :hold, "
                        + "authorization_expires_at = :expiresAt WHERE id = :id AND status = 'PENDING'")
                .param("hold", ledgerHoldId)
                .param("expiresAt", authorizationExpiresAt)
                .param("id", id)
                .update() == 1;
    }

    /** Two-step payments, step two: the ledger moved the money. False if the payment was no longer authorized. */
    public boolean markCaptured(UUID id, UUID ledgerTransactionId) {
        return jdbc.sql("UPDATE payments SET status = 'COMPLETED', ledger_transaction_id = :transactionId "
                        + "WHERE id = :id AND status = 'AUTHORIZED'")
                .param("transactionId", ledgerTransactionId)
                .param("id", id)
                .update() == 1;
    }

    /** The ledger released the hold. False if the payment was no longer authorized. */
    public boolean markCancelled(UUID id) {
        return jdbc.sql("UPDATE payments SET status = 'CANCELLED' WHERE id = :id AND status = 'AUTHORIZED'")
                .param("id", id)
                .update() == 1;
    }

    /** The hold ran out. False if the payment was no longer authorized. */
    public boolean markExpired(UUID id) {
        return jdbc.sql("UPDATE payments SET status = 'EXPIRED' WHERE id = :id AND status = 'AUTHORIZED'")
                .param("id", id)
                .update() == 1;
    }

    /** Marks the COMPLETED payment as REVERSED. Returns how many changed (0 if it was not COMPLETED). */
    public int markReversed(UUID paymentId) {
        return jdbc.sql("UPDATE payments SET status = 'REVERSED' WHERE id = :id AND status = 'COMPLETED'")
                .param("id", paymentId)
                .update();
    }

    /** Pending payments nobody has touched for {@code olderThanSeconds}, oldest first. */
    public List<UUID> findStalePending(int olderThanSeconds, int limit) {
        return jdbc.sql("SELECT id FROM payments WHERE status = 'PENDING' "
                        + "AND updated_at < clock_timestamp() - make_interval(secs => CAST(:seconds AS double precision)) "
                        + "ORDER BY updated_at LIMIT :limit")
                .param("seconds", olderThanSeconds)
                .param("limit", limit)
                .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                .list();
    }

    /**
     * Authorized payments whose authorization ended more than {@code graceSeconds} ago (by the database's clock), the
     * oldest first. The grace period leaves the ledger's hold events time to settle them first.
     */
    public List<UUID> findDueAuthorizations(int graceSeconds, int limit) {
        return jdbc.sql("SELECT id FROM payments WHERE status = 'AUTHORIZED' "
                        + "AND authorization_expires_at <= clock_timestamp() - (:grace * interval '1 second') "
                        + "ORDER BY authorization_expires_at LIMIT :limit")
                .param("grace", graceSeconds)
                .param("limit", limit)
                .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                .list();
    }
}