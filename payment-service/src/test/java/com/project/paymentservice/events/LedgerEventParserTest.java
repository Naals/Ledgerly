package com.project.paymentservice.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The consumer side of the TransactionPosted v1 contract, using the same sample files as the producer. */
class LedgerEventParserTest {

    private static final Path CONTRACTS = Path.of("..", "contracts");
    private static final UUID TRANSFER_ID = UUID.fromString("7d1f6a0e-2b1c-4c58-9a57-0c6f0d9a1b11");
    private static final UUID REVERSAL_ID = UUID.fromString("2b9c3a54-8f0e-4a39-bb52-6d1e7f3c0a22");
    private static final UUID PAYMENT_ID = UUID.fromString("5e0d9c6b-1f3a-4f0a-9b8e-2a7c4d1e8f33");

    private static String sample(String file) throws IOException {
        return Files.readString(CONTRACTS.resolve(file));
    }

    @Test
    void readsATransferEvent() throws IOException {
        LedgerEvent event = LedgerEventParser.parse(sample("transaction-posted.v1.transfer.json"));

        assertThat(event.version()).isEqualTo(1);
        assertThat(event.transactionId()).isEqualTo(TRANSFER_ID);
        assertThat(event.type()).isEqualTo("TRANSFER");
        assertThat(event.isReversal()).isFalse();
        assertThat(event.reversesTransactionId()).isNull();
    }

    @Test
    void readsAReversalEventAndTheTransactionItReverses() throws IOException {
        LedgerEvent event = LedgerEventParser.parse(sample("transaction-posted.v1.reversal.json"));

        assertThat(event.transactionId()).isEqualTo(REVERSAL_ID);
        assertThat(event.isReversal()).isTrue();
        assertThat(event.reversesTransactionId()).isEqualTo(TRANSFER_ID);
    }

    @Test
    void aReversalNamesThePaymentThroughTheReferenceItInheritedFromTheTransfer() throws IOException {
        LedgerEvent transfer = LedgerEventParser.parse(sample("transaction-posted.v1.transfer.json"));
        LedgerEvent reversal = LedgerEventParser.parse(sample("transaction-posted.v1.reversal.json"));

        assertThat(transfer.paymentId()).contains(PAYMENT_ID);
        assertThat(reversal.paymentId()).contains(PAYMENT_ID);
    }

    @Test
    void aReferenceThatIsNotAPaymentIdNamesNoPayment() throws IOException {
        String someoneElses = sample("transaction-posted.v1.reversal.json")
                .replace("5e0d9c6b-1f3a-4f0a-9b8e-2a7c4d1e8f33", "invoice-42");
        String none = sample("transaction-posted.v1.reversal.json")
                .replace("\"reference\": \"5e0d9c6b-1f3a-4f0a-9b8e-2a7c4d1e8f33\",", "");

        assertThat(LedgerEventParser.parse(someoneElses).paymentId()).isEmpty();
        assertThat(LedgerEventParser.parse(none).paymentId()).isEmpty();
    }

    @Test
    void ignoresFieldsTheProducerAddsLater() throws IOException {
        String withNewField = sample("transaction-posted.v1.reversal.json")
                .replace("\"version\": 1,", "\"version\": 1, \"extra\": {\"a\": [1, 2]},");

        assertThat(LedgerEventParser.parse(withNewField).reversesTransactionId()).isEqualTo(TRANSFER_ID);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> LedgerEventParser.parse("this is not json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnEventWithoutATransactionIdOrType() {
        assertThatThrownBy(() -> LedgerEventParser.parse("{\"version\": 1, \"type\": \"TRANSFER\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LedgerEventParser.parse(
                "{\"version\": 1, \"transactionId\": \"7d1f6a0e-2b1c-4c58-9a57-0c6f0d9a1b11\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTransferIsATransferAndNotAReversal() throws IOException {
        LedgerEvent event = LedgerEventParser.parse(sample("transaction-posted.v1.transfer.json"));

        assertThat(event.isTransfer()).isTrue();
        assertThat(event.isReversal()).isFalse();
    }
}