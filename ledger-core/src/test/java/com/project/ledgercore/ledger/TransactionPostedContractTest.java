package com.project.ledgercore.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.ledgercore.common.Money;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The producer side of the TransactionPosted v1 contract. The sample files in /contracts are shared with the
 * consumers' tests: if this test fails because the event changed, the consumers need to hear about it.
 */
class TransactionPostedContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path CONTRACTS = Path.of("..", "contracts");

    private static final UUID TRANSFER_ID = UUID.fromString("7d1f6a0e-2b1c-4c58-9a57-0c6f0d9a1b11");
    private static final UUID REVERSAL_ID = UUID.fromString("2b9c3a54-8f0e-4a39-bb52-6d1e7f3c0a22");
    private static final UUID ALICE = UUID.fromString("0c8f4f2e-6b0e-4d0e-8f3a-1a2b3c4d5e01");
    private static final UUID BOB = UUID.fromString("0c8f4f2e-6b0e-4d0e-8f3a-1a2b3c4d5e02");

    @Test
    void aTransferEventMatchesTheSample() throws IOException {
        PostingRequest transfer = new PostingRequest(TransactionType.TRANSFER, "Transfer", List.of(
                PostingLine.debit(ALICE, Money.of(100_000, "KZT")),
                PostingLine.credit(BOB, Money.of(100_000, "KZT"))));

        assertThat(asJson(TransactionPostedEvent.of(TRANSFER_ID, transfer)))
                .isEqualTo(sample("transaction-posted.v1.transfer.json"));
    }

    @Test
    void aReversalEventMatchesTheSample() throws IOException {
        PostingRequest reversal = new PostingRequest(
                TransactionType.REVERSAL,
                "Reversal of " + TRANSFER_ID + ": sent to the wrong account",
                List.of(PostingLine.credit(ALICE, Money.of(100_000, "KZT")),
                        PostingLine.debit(BOB, Money.of(100_000, "KZT"))),
                TRANSFER_ID);

        assertThat(asJson(TransactionPostedEvent.of(REVERSAL_ID, reversal)))
                .isEqualTo(sample("transaction-posted.v1.reversal.json"));
    }

    @Test
    void theSamplesDeclareVersionOne() throws IOException {
        assertThat(sample("transaction-posted.v1.transfer.json").get("version").asInt())
                .isEqualTo(TransactionPostedEvent.VERSION);
        assertThat(sample("transaction-posted.v1.reversal.json").get("version").asInt())
                .isEqualTo(TransactionPostedEvent.VERSION);
    }

    /** Written out and read back, so numbers compare the way a consumer would see them. */
    private static JsonNode asJson(TransactionPostedEvent event) throws IOException {
        return JSON.readTree(JSON.writeValueAsString(event));
    }

    private static JsonNode sample(String file) throws IOException {
        return JSON.readTree(Files.readString(CONTRACTS.resolve(file)));
    }
}