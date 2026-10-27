package com.project.ledgercore.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.ledgercore.common.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransactionPostedEventTest {

    private final UUID transactionId = UUID.randomUUID();
    private final UUID from = UUID.randomUUID();
    private final UUID to = UUID.randomUUID();

    private PostingRequest transfer() {
        return new PostingRequest(TransactionType.TRANSFER, "rent", List.of(
                PostingLine.debit(from, Money.of(100, "KZT")),
                PostingLine.credit(to, Money.of(100, "KZT"))));
    }

    @Test
    void mapsEveryLineOfThePosting() {
        TransactionPostedEvent event = TransactionPostedEvent.of(transactionId, transfer());

        assertThat(event.transactionId()).isEqualTo(transactionId);
        assertThat(event.type()).isEqualTo("TRANSFER");
        assertThat(event.description()).isEqualTo("rent");
        assertThat(event.entries()).containsExactly(
                new TransactionPostedEvent.Line(from, -100, "KZT"),
                new TransactionPostedEvent.Line(to, 100, "KZT"));
    }

    @Test
    void carriesTheContractVersionAndTheReversedTransaction() {
        UUID original = UUID.randomUUID();
        PostingRequest reversal = new PostingRequest(TransactionType.REVERSAL, "undo", List.of(
                PostingLine.credit(from, Money.of(100, "KZT")),
                PostingLine.debit(to, Money.of(100, "KZT"))), original);

        TransactionPostedEvent event = TransactionPostedEvent.of(transactionId, reversal);

        assertThat(event.version()).isEqualTo(1);
        assertThat(event.type()).isEqualTo("REVERSAL");
        assertThat(event.reversesTransactionId()).isEqualTo(original);
    }

    @Test
    void serialisesToTheDocumentedJsonShape() {
        JsonNode json = new ObjectMapper().valueToTree(TransactionPostedEvent.of(transactionId, transfer()));

        assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                "version", "transactionId", "type", "description", "reversesTransactionId", "entries");
        assertThat(json.get("version").asInt()).isEqualTo(1);
        assertThat(json.get("transactionId").asText()).isEqualTo(transactionId.toString());
        assertThat(json.get("reversesTransactionId").isNull()).isTrue();

        JsonNode firstEntry = json.get("entries").get(0);
        assertThat(fieldNames(firstEntry)).containsExactlyInAnyOrder("accountId", "amountMinor", "currency");
        assertThat(firstEntry.get("accountId").asText()).isEqualTo(from.toString());
        assertThat(firstEntry.get("amountMinor").asLong()).isEqualTo(-100);
        assertThat(firstEntry.get("currency").asText()).isEqualTo("KZT");
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}