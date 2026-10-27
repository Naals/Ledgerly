package com.project.ledgercore.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.BusinessRuleException;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.outbox.OutboxEvent;
import com.project.ledgercore.outbox.OutboxRepository;
import com.project.ledgercore.reversal.ReversalService;
import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.transfer.TransferService;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class TransactionPostedEventIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    LedgerService ledger;

    @Autowired
    AccountRepository accounts;

    @Autowired
    CashService cash;

    @Autowired
    TransferService transfers;

    @Autowired
    ReversalService reversals;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void everyPostingWritesOneEventInTheSameTransaction() throws Exception {
        UUID transactionId = transferBetweenNewAccounts();

        List<OutboxEvent> events = outbox.findByAggregate(transactionId);

        assertThat(events).hasSize(1);
        OutboxEvent event = events.getFirst();
        assertThat(event.eventType()).isEqualTo("TransactionPosted");
        assertThat(event.aggregateType()).isEqualTo("transaction");
        JsonNode payload = json.readTree(event.payload());
        assertThat(payload.get("version").asInt()).isEqualTo(1);
        assertThat(payload.get("transactionId").asText()).isEqualTo(transactionId.toString());
        assertThat(payload.get("type").asText()).isEqualTo("TRANSFER");
    }

    @Test
    void theEventMatchesTheStoredEntries() throws Exception {
        UUID transactionId = transferBetweenNewAccounts();

        JsonNode payload = json.readTree(outbox.findByAggregate(transactionId).getFirst().payload());
        Set<String> fromEvent = new HashSet<>();
        for (JsonNode line : payload.get("entries")) {
            fromEvent.add(line.get("accountId").asText() + ":" + line.get("amountMinor").asLong());
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT account_id, amount FROM ledger_entries WHERE transaction_id = ?", transactionId);
        Set<String> fromLedger = new HashSet<>();
        for (Map<String, Object> row : rows) {
            fromLedger.add(row.get("account_id") + ":" + row.get("amount"));
        }

        assertThat(fromEvent).hasSize(2).isEqualTo(fromLedger);
    }

    @Test
    void aRefusedPostingWritesNoEvent() {
        Account funding = accounts.create(NewAccount.system("event-funding-" + UUID.randomUUID(), KZT));
        Account alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        Long before = jdbc.queryForObject("SELECT count(*) FROM outbox_events", Long.class);

        assertThatThrownBy(() -> ledger.post(new PostingRequest(TransactionType.WITHDRAWAL, "too much", List.of(
                PostingLine.debit(alice.id(), Money.of(100, "KZT")),
                PostingLine.credit(funding.id(), Money.of(100, "KZT"))))))
                .isInstanceOf(BusinessRuleException.class);

        Long after = jdbc.queryForObject("SELECT count(*) FROM outbox_events", Long.class);
        assertThat(after).isEqualTo(before);
    }

    @Test
    void aReversalEventNamesTheTransactionItReverses() throws Exception {
        UUID transfer = transferBetweenNewAccounts();

        UUID reversal = reversals.reverse(transfer, "test");

        JsonNode payload = json.readTree(outbox.findByAggregate(reversal).getFirst().payload());
        assertThat(payload.get("type").asText()).isEqualTo("REVERSAL");
        assertThat(payload.get("reversesTransactionId").asText()).isEqualTo(transfer.toString());
    }

    private UUID transferBetweenNewAccounts() {
        Account alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        Account bob = accounts.create(NewAccount.customer("bob-" + UUID.randomUUID(), KZT));
        cash.deposit(alice.id(), Money.of(1_000, "KZT"));
        return transfers.transfer(alice.id(), bob.id(), Money.of(400, "KZT"));
    }
}