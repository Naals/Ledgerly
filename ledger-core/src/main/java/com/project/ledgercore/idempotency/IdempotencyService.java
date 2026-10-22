package com.project.ledgercore.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs an operation at most once per (scope, key) and replays the stored response on retries.
 *
 * <p>The key is claimed, the operation runs and the response is stored in ONE database transaction.
 * A concurrent duplicate waits for that transaction, then replays its result. If the operation fails,
 * everything rolls back, key included, so a retry simply runs again.
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository keys;
    private final ObjectMapper json;

    public IdempotencyService(IdempotencyRepository keys, ObjectMapper json) {
        this.keys = keys;
        this.json = json;
    }

    @Transactional
    public <T> Outcome<T> execute(String scope, String key, String requestHash,
                                  Class<T> responseType, Supplier<T> operation) {
        if (keys.claim(scope, key, requestHash)) {
            T response = operation.get();
            keys.complete(scope, key, serialize(response));
            return new Outcome<>(response, false);
        }

        IdempotencyRepository.StoredRequest stored = keys.find(scope, key)
                .orElseThrow(() -> new IllegalStateException("Idempotency key vanished: " + scope + "/" + key));
        if (!stored.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReuseException(key);
        }
        if (stored.responseBody() == null) {
            throw new IllegalStateException("Idempotency record has no response: " + scope + "/" + key);
        }
        return new Outcome<>(deserialize(stored.responseBody(), responseType), true);
    }

    /** The response, and whether it was replayed from an earlier identical request. */
    public record Outcome<T>(T response, boolean replayed) {
    }

    private String serialize(Object response) {
        try {
            return json.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot store idempotent response", e);
        }
    }

    private <T> T deserialize(String body, Class<T> type) {
        try {
            return json.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read stored idempotent response", e);
        }
    }
}
