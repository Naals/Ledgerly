package com.project.paymentservice.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Decides what an HTTP answer from the ledger means for a payment.
 *
 * <p>The important distinction: a refusal is final (the payment fails), while anything that leaves the
 * outcome unknown (a server error, a timeout, an answer that cannot be trusted) is not (the payment stays
 * pending and is retried). Retrying is safe because the ledger deduplicates on the idempotency key.
 */
public final class LedgerResponseInterpreter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LedgerResponseInterpreter() {
    }

    /** The answer to a one-step transfer. */
    public static LedgerResult interpret(int status, String body) {
        if (status == 200 || status == 201) {
            return transactionIdOf(body)
                    .<LedgerResult>map(LedgerResult.Completed::new)
                    .orElseGet(() -> new LedgerResult.Unavailable(
                            "The ledger answered " + status + " without a usable transactionId"));
        }
        if (status == 408 || status == 429 || status >= 500) {
            return new LedgerResult.Unavailable("The ledger answered " + status);
        }
        if (status >= 400) {
            return new LedgerResult.Rejected(LedgerProblems.codeOf(body));
        }
        return new LedgerResult.Unavailable("Unexpected answer from the ledger: " + status);
    }

    /** The answer to placing, reading or releasing a hold: the hold. */
    public static LedgerOutcome<HoldInfo> interpretHold(int status, String body) {
        return interpret(status, body, LedgerResponseInterpreter::holdOf);
    }

    /** The answer to a capture: the ledger transaction that moved the money. */
    public static LedgerOutcome<UUID> interpretCapture(int status, String body) {
        return interpret(status, body, json -> uuidField(json, "transactionId"));
    }

    private static <T> LedgerOutcome<T> interpret(int status, String body, Function<JsonNode, Optional<T>> reader) {
        if (status == 200 || status == 201) {
            return parse(body)
                    .flatMap(reader)
                    .<LedgerOutcome<T>>map(LedgerOutcome.Done::new)
                    .orElseGet(() -> new LedgerOutcome.Unavailable<>(
                            "The ledger answered " + status + " without a usable body"));
        }
        if (status == 408 || status == 429 || status >= 500) {
            return new LedgerOutcome.Unavailable<>("The ledger answered " + status);
        }
        if (status >= 400) {
            return new LedgerOutcome.Rejected<>(LedgerProblems.codeOf(body));
        }
        return new LedgerOutcome.Unavailable<>("Unexpected answer from the ledger: " + status);
    }

    private static Optional<HoldInfo> holdOf(JsonNode json) {
        try {
            UUID id = UUID.fromString(requiredText(json, "id"));
            HoldState state = HoldState.valueOf(requiredText(json, "status"));
            OffsetDateTime expiresAt = OffsetDateTime.parse(requiredText(json, "expiresAt"));
            JsonNode transaction = json.get("ledgerTransactionId");
            UUID transactionId = transaction == null || transaction.isNull() ? null : UUID.fromString(transaction.asText());
            return Optional.of(new HoldInfo(id, state, expiresAt, transactionId));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static Optional<UUID> transactionIdOf(String body) {
        return parse(body).flatMap(json -> uuidField(json, "transactionId"));
    }

    private static Optional<UUID> uuidField(JsonNode json, String field) {
        JsonNode value = json.get(field);
        try {
            return value != null && value.isTextual() ? Optional.of(UUID.fromString(value.asText())) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String requiredText(JsonNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("missing " + field);
        }
        return value.asText();
    }

    private static Optional<JsonNode> parse(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readTree(body));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}