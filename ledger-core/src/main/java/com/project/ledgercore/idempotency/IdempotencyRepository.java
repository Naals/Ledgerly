package com.project.ledgercore.idempotency;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Tries to claim the key. Returns true for the first caller. If another transaction is claiming
     * the same key right now, this call waits until that transaction ends, then returns false
     * (or true, if the other transaction rolled back).
     */
    public boolean claim(String scope, String key, String requestHash) {
        int inserted = jdbc.sql("INSERT INTO idempotency_keys (scope, idempotency_key, request_hash) "
                        + "VALUES (:scope, :key, :requestHash) "
                        + "ON CONFLICT (scope, idempotency_key) DO NOTHING")
                .param("scope", scope)
                .param("key", key)
                .param("requestHash", requestHash)
                .update();
        return inserted == 1;
    }

    public void complete(String scope, String key, String responseBody) {
        jdbc.sql("UPDATE idempotency_keys SET response_body = :responseBody "
                        + "WHERE scope = :scope AND idempotency_key = :key")
                .param("scope", scope)
                .param("key", key)
                .param("responseBody", responseBody)
                .update();
    }

    public Optional<StoredRequest> find(String scope, String key) {
        return jdbc.sql("SELECT request_hash, response_body FROM idempotency_keys "
                        + "WHERE scope = :scope AND idempotency_key = :key")
                .param("scope", scope)
                .param("key", key)
                .query((rs, rowNum) -> new StoredRequest(rs.getString("request_hash"), rs.getString("response_body")))
                .optional();
    }

    public record StoredRequest(String requestHash, String responseBody) {
    }
}
