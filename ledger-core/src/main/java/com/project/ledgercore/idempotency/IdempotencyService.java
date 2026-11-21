package com.project.ledgercore.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.ledgercore.common.LedgerException;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs an operation at most once per (scope, key) and replays the stored response on retries.
 *
 * <p>The key is claimed, the operation runs and the response is stored in ONE database transaction.
 * A concurrent duplicate waits for that transaction, then replays its result. Initial payment
 * instructions opt into durable business rejections; other operations retain retryable refusals.
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository keys;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final TransactionTemplate operationTransaction;

    public IdempotencyService(IdempotencyRepository keys, ObjectMapper json, PlatformTransactionManager transactions) {
        this.keys = keys;
        this.json = json;
        this.transaction = new TransactionTemplate(transactions);
        this.operationTransaction = new TransactionTemplate(transactions);
        this.operationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /** A failed operation rolls back its key, allowing a retry after the cause has been resolved. */
    @Transactional
    public <T> Outcome<T> execute(String scope, String key, String requestHash,
                                  Class<T> responseType, Supplier<T> operation) {
        return outcomeOf(executeInTransaction(scope, key, requestHash, responseType, operation, false));
    }

    /**
     * Initial transfers and authorizations need one final outcome, including refusals. Must be called
     * outside a transaction so the saved outcome commits before a rejection is thrown.
     */
    @Transactional(propagation = Propagation.NEVER)
    public <T> Outcome<T> executeWithDurableRejections(String scope, String key, String requestHash,
                                                     Class<T> responseType, Supplier<T> operation) {
        Execution<T> result = transaction.execute(status ->
                executeInTransaction(scope, key, requestHash, responseType, operation, true));
        return outcomeOf(result);
    }

    private <T> Outcome<T> outcomeOf(Execution<T> result) {
        if (result.rejection() != null) {
            throw result.rejection().asException(result.replayed());
        }
        return new Outcome<>(result.response(), result.replayed());
    }

    private <T> Execution<T> executeInTransaction(String scope, String key, String requestHash,
                                                 Class<T> responseType, Supplier<T> operation,
                                                 boolean durableRejections) {
        if (keys.claim(scope, key, requestHash)) {
            T response;
            try {
                response = durableRejections
                        ? operationTransaction.execute(status -> operation.get())
                        : operation.get();
            } catch (LedgerException exception) {
                if (!durableRejections) {
                    throw exception;
                }
                // Rolling back the nested transaction also clears rollback-only state set by a
                // participating @Transactional service, and undoes all its ledger/outbox writes.
                StoredRejection rejection = StoredRejection.from(exception);
                keys.reject(scope, key, rejection);
                return new Execution<>(null, false, rejection);
            }
            keys.complete(scope, key, serialize(response));
            return new Execution<>(response, false, null);
        }

        IdempotencyRepository.StoredRequest stored = keys.find(scope, key)
                .orElseThrow(() -> new IllegalStateException("Idempotency key vanished: " + scope + "/" + key));
        if (!stored.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReuseException(key);
        }
        if (stored.rejection() != null) {
            return new Execution<>(null, true, stored.rejection());
        }
        if (stored.responseBody() == null) {
            throw new IllegalStateException("Idempotency record has no response: " + scope + "/" + key);
        }
        return new Execution<>(deserialize(stored.responseBody(), responseType), true, null);
    }

    private record Execution<T>(T response, boolean replayed, StoredRejection rejection) {
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
