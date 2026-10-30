package com.project.paymentservice.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

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

    private static Optional<UUID> transactionIdOf(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode id = JSON.readTree(body).get("transactionId");
            return id != null && id.isTextual() ? Optional.of(UUID.fromString(id.asText())) : Optional.empty();
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}