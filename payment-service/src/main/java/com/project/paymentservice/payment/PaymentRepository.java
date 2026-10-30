package com.project.paymentservice.payment;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {

    private static final String COLUMNS = "id, idempotency_key, request_hash, from_account_id, to_account_id, "
            + "amount_minor, currency, status, failure_code, ledger_transaction_id, attempts, created_at, updated_at";

    private static final RowMapper<Payment> ROW_MAPPER = (rs, rowNum) -> new Payment(
            rs.getObject("id", UUID.class),
            rs.getString("idempotency_key"),
            rs.getString("request_hash"),
            rs.getObject("from_account_id", UUID.class),
            rs.getObject("to_account_id", UUID.class),
            rs.getLong("amount_minor"),
            rs.getString("currency"),
            PaymentStatus.valueOf(rs.getString("status")),
            rs.getString("failure_code"),
            rs.getObject("ledger_transaction_id", UUID.class),
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
                                      UUID toAccountId, long amountMinor, String currency) {
        return jdbc.sql("INSERT INTO payments (idempotency_key, request_hash, from_account_id, to_account_id, "
                        + "amount_minor, currency) "
                        + "VALUES (:key, :hash, :fromAccount, :toAccount, :amount, :currency) "
                        + "ON CONFLICT (idempotency_key) DO NOTHING RETURNING id")
                .param("key", idempotencyKey)
                .param("hash", requestHash)
                .param("fromAccount", fromAccountId)
                .param("toAccount", toAccountId)
                .param("amount", amountMinor)
                .param("currency", currency)
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
}