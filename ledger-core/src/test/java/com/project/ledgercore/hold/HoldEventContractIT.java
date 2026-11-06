package com.project.ledgercore.hold;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.support.LedgerFixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The producer side of the HoldStatusChanged v1 contract. The sample files in /contracts are what payment-service tests
 * against: if this fails because the event changed, the consumers need to hear about it.
 */
class HoldEventContractIT extends AbstractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path CONTRACTS = Path.of("..", "contracts");

    private static final UUID ALICE = UUID.fromString("0c8f4f2e-6b0e-4d0e-8f3a-1a2b3c4d5e01");
    private static final UUID BOB = UUID.fromString("0c8f4f2e-6b0e-4d0e-8f3a-1a2b3c4d5e02");
    private static final UUID CAPTURED = UUID.fromString("5a7c9e21-3b4d-4f68-8c0a-6e1d2f3b4c55");
    private static final UUID RELEASED = UUID.fromString("6b8d0f32-4c5e-4079-9d1b-7f2e3a4c5d66");
    private static final UUID EXPIRED = UUID.fromString("7c9e1a43-5d6f-418a-8e2c-803f4b5d6e77");
    private static final UUID TRANSACTION = UUID.fromString("7d1f6a0e-2b1c-4c58-9a57-0c6f0d9a1b11");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    LedgerFixtures fixtures;

    @Test
    void theEventsOfAHoldMatchTheirContractSamples() throws IOException {
        for (UUID account : List.of(ALICE, BOB)) {
            jdbc.update("INSERT INTO accounts (id, owner_ref, type, currency) VALUES (?, ?, 'CUSTOMER', 'KZT')",
                    account, "contract-" + account);
        }
        UUID bank = fixtures.systemAccount("KZT");
        tx.executeWithoutResult(status -> {
            UUID funding = UUID.randomUUID();
            fixtures.insertTransaction(funding);
            fixtures.insertEntry(funding, bank, -1_000_000, "KZT");
            fixtures.insertEntry(funding, ALICE, 1_000_000, "KZT");
        });
        insertHold(CAPTURED, 100_000, "3f6a1b2c-9d4e-4f70-8a15-5c2e7b9d0a11", "2099-01-01T00:00:00Z");
        insertHold(RELEASED, 250_000, "9b2d4c61-0e7a-4b83-a6f5-1d3c8e5f7a22", "2099-01-01T00:00:00Z");
        insertHold(EXPIRED, 40_000, null, "2026-01-15T10:00:00Z");

        // a capture, in the order HoldService does it: free the hold, post the transfer, record the transfer on the hold
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE holds SET status = 'CAPTURED', captured_minor = 100000 WHERE id = ?", CAPTURED);
            jdbc.update("INSERT INTO ledger_transactions (id, type, description, reference) "
                    + "VALUES (?, 'TRANSFER', 'capture', '3f6a1b2c-9d4e-4f70-8a15-5c2e7b9d0a11')", TRANSACTION);
            fixtures.insertEntry(TRANSACTION, ALICE, -100_000, "KZT");
            fixtures.insertEntry(TRANSACTION, BOB, 100_000, "KZT");
            jdbc.update("UPDATE holds SET ledger_transaction_id = ? WHERE id = ?", TRANSACTION, CAPTURED);
        });
        jdbc.update("UPDATE holds SET status = 'RELEASED' WHERE id = ?", RELEASED);
        jdbc.update("UPDATE holds SET status = 'EXPIRED' WHERE id = ?", EXPIRED);

        assertMatches(eventOf(CAPTURED), "hold-status-changed.v1.captured.json");
        assertMatches(eventOf(RELEASED), "hold-status-changed.v1.released.json");
        assertMatches(eventOf(EXPIRED), "hold-status-changed.v1.expired.json");
    }

    @Test
    void theSamplesDeclareVersionOne() throws IOException {
        for (String name : List.of("captured", "released", "expired")) {
            assertThat(sample("hold-status-changed.v1." + name + ".json").get("version").asInt()).isEqualTo(1);
        }
    }

    private void insertHold(UUID id, long amount, String reference, String expiresAt) {
        jdbc.update("INSERT INTO holds (id, account_id, to_account_id, amount_minor, currency, reference, expires_at) "
                + "VALUES (?, ?, ?, ?, 'KZT', ?, CAST(? AS timestamptz))", id, ALICE, BOB, amount, reference, expiresAt);
    }

    private JsonNode eventOf(UUID hold) throws IOException {
        String text = jdbc.queryForObject("SELECT CAST(payload AS text) FROM outbox_events WHERE aggregate_id = ?",
                String.class, hold);
        return JSON.readTree(text);
    }

    /** Everything must match except occurredAt, which is the database clock's and not the test's. */
    private static void assertMatches(JsonNode actual, String sampleFile) throws IOException {
        JsonNode expected = sample(sampleFile);
        ((ObjectNode) actual).put("occurredAt", expected.get("occurredAt").asText());
        assertThat(actual).as(sampleFile).isEqualTo(expected);
    }

    private static JsonNode sample(String file) throws IOException {
        return JSON.readTree(Files.readString(CONTRACTS.resolve(file)));
    }
}