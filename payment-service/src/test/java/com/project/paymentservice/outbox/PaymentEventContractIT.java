package com.project.paymentservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.project.paymentservice.support.AbstractIntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The producer side of the PaymentStatusChanged v1 contract. The sample files in /contracts are what consumers
 * test against: if this fails because the event changed, the consumers need to hear about it.
 */
class PaymentEventContractIT extends AbstractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path CONTRACTS = Path.of("..", "contracts");

    private static final UUID MANUAL_PAYMENT = UUID.fromString("3f6a1b2c-9d4e-4f70-8a15-5c2e7b9d0a11");
    private static final UUID FAILED_PAYMENT = UUID.fromString("9b2d4c61-0e7a-4b83-a6f5-1d3c8e5f7a22");
    private static final UUID TRANSACTION = UUID.fromString("7d1f6a0e-2b1c-4c58-9a57-0c6f0d9a1b11");
    private static final UUID ALICE = UUID.fromString("0c8f4f2e-6b0e-4d0e-8f3a-1a2b3c4d5e01");
    private static final UUID BOB = UUID.fromString("0c8f4f2e-6b0e-4d0e-8f3a-1a2b3c4d5e02");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theEventsOfAPaymentMatchTheirContractSamples() throws IOException {
        jdbc.update("INSERT INTO payments (id, idempotency_key, request_hash, from_account_id, to_account_id, "
                        + "amount_minor, currency, capture_mode) VALUES (?, 'contract-1', ?, ?, ?, 100000, 'KZT', 'MANUAL')",
                MANUAL_PAYMENT, "a".repeat(64), ALICE, BOB);
        jdbc.update("INSERT INTO payments (id, idempotency_key, request_hash, from_account_id, to_account_id, "
                        + "amount_minor, currency, capture_mode) VALUES (?, 'contract-2', ?, ?, ?, 250000, 'KZT', 'AUTOMATIC')",
                FAILED_PAYMENT, "b".repeat(64), ALICE, BOB);
        jdbc.update("UPDATE payments SET status = 'AUTHORIZED', ledger_hold_id = ?, "
                + "authorization_expires_at = '2026-11-04T09:30:00Z' WHERE id = ?", UUID.randomUUID(), MANUAL_PAYMENT);
        jdbc.update("UPDATE payments SET status = 'COMPLETED', ledger_transaction_id = ? WHERE id = ?",
                TRANSACTION, MANUAL_PAYMENT);
        jdbc.update("UPDATE payments SET status = 'FAILED', failure_code = 'insufficient-funds' WHERE id = ?",
                FAILED_PAYMENT);

        List<JsonNode> manual = eventsOf(MANUAL_PAYMENT);
        List<JsonNode> failed = eventsOf(FAILED_PAYMENT);

        assertMatches(manual.get(0), "payment-status-changed.v1.authorized.json");
        assertMatches(manual.get(1), "payment-status-changed.v1.completed.json");
        assertMatches(failed.getFirst(), "payment-status-changed.v1.failed.json");
    }

    @Test
    void theSamplesDeclareVersionOne() throws IOException {
        for (String file : List.of("authorized", "completed", "failed")) {
            assertThat(sample("payment-status-changed.v1." + file + ".json").get("version").asInt()).isEqualTo(1);
        }
    }

    /** Everything must match except occurredAt, which is the database clock's and not the test's. */
    private static void assertMatches(JsonNode actual, String sampleFile) throws IOException {
        JsonNode expected = sample(sampleFile);
        ((ObjectNode) actual).put("occurredAt", expected.get("occurredAt").asText());
        assertThat(actual).as(sampleFile).isEqualTo(expected);
    }

    private List<JsonNode> eventsOf(UUID paymentId) {
        return jdbc.queryForList("SELECT CAST(payload AS text) FROM outbox_events WHERE aggregate_id = ? ORDER BY seq",
                String.class, paymentId).stream().map(text -> {
            try {
                return JSON.readTree(text);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    private static JsonNode sample(String file) throws IOException {
        return JSON.readTree(Files.readString(CONTRACTS.resolve(file)));
    }
}