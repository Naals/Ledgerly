package com.project.paymentservice.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.paymentservice.ledger.HoldState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HoldEventParserTest {

    private static final Path CONTRACTS = Path.of("..", "contracts");
    private static final UUID PAYMENT = UUID.fromString("3f6a1b2c-9d4e-4f70-8a15-5c2e7b9d0a11");
    private static final UUID TRANSACTION = UUID.fromString("7d1f6a0e-2b1c-4c58-9a57-0c6f0d9a1b11");

    private static String sample(String name) throws IOException {
        return Files.readString(CONTRACTS.resolve("hold-status-changed.v1." + name + ".json"));
    }

    @Test
    void readsACapturedHoldWithTheTransactionThatCapturedIt() throws IOException {
        HoldEvent event = HoldEventParser.parse(sample("captured"));

        assertThat(event.state()).isEqualTo(HoldState.CAPTURED);
        assertThat(event.ledgerTransactionId()).isEqualTo(TRANSACTION);
        assertThat(event.paymentId()).contains(PAYMENT);
    }

    @Test
    void readsAReleasedHold() throws IOException {
        HoldEvent event = HoldEventParser.parse(sample("released"));

        assertThat(event.state()).isEqualTo(HoldState.RELEASED);
        assertThat(event.ledgerTransactionId()).isNull();
        assertThat(event.paymentId()).isPresent();
    }

    @Test
    void anExpiredHoldWithoutAReferenceBelongsToNoPayment() throws IOException {
        HoldEvent event = HoldEventParser.parse(sample("expired"));

        assertThat(event.state()).isEqualTo(HoldState.EXPIRED);
        assertThat(event.reference()).isNull();
        assertThat(event.paymentId()).isEmpty();
    }

    @Test
    void aReferenceThatIsNotAPaymentIdBelongsToNoPayment() throws IOException {
        String someoneElses = sample("released").replace("9b2d4c61-0e7a-4b83-a6f5-1d3c8e5f7a22", "invoice-42");

        assertThat(HoldEventParser.parse(someoneElses).paymentId()).isEmpty();
    }

    @Test
    void ignoresFieldsTheLedgerAddsLater() throws IOException {
        String withNewField = sample("released").replace("\"version\": 1,", "\"version\": 1, \"extra\": {\"a\": [1, 2]},");

        assertThat(HoldEventParser.parse(withNewField).state()).isEqualTo(HoldState.RELEASED);
    }

    @Test
    void refusesWhatItCannotTrust() throws IOException {
        assertThatThrownBy(() -> HoldEventParser.parse("this is not json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HoldEventParser.parse("{\"status\": \"RELEASED\"}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HoldEventParser.parse(sample("released").replace("RELEASED", "PARTIALLY_CAPTURED")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HoldEventParser.parse(
                sample("captured").replace(TRANSACTION.toString(), "null").replace("\"null\"", "null")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transaction");
    }
}