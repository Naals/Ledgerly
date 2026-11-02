package com.project.ledgercore.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class TransactionReferenceIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    LedgerService ledger;

    @Autowired
    AccountRepository accounts;

    @Autowired
    CashService cash;

    @Autowired
    JdbcTemplate jdbc;

    private Account alice;
    private Account bob;

    @BeforeEach
    void openAccounts() {
        alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        bob = accounts.create(NewAccount.customer("bob-" + UUID.randomUUID(), KZT));
        cash.deposit(alice.id(), Money.of(1_000, "KZT"));
    }

    private UUID transfer(String reference) {
        return ledger.post(new PostingRequest(TransactionType.TRANSFER, "rent", List.of(
                PostingLine.debit(alice.id(), Money.of(100, "KZT")),
                PostingLine.credit(bob.id(), Money.of(100, "KZT"))), null, reference));
    }

    @Test
    void theReferenceIsStoredWithTheTransaction() {
        UUID id = transfer("payment-42");

        assertThat(jdbc.queryForObject("SELECT reference FROM ledger_transactions WHERE id = ?", String.class, id))
                .isEqualTo("payment-42");
    }

    @Test
    void aTransactionWithoutAReferenceStoresNone() {
        UUID id = transfer(null);

        assertThat(jdbc.queryForObject("SELECT reference FROM ledger_transactions WHERE id = ?", String.class, id))
                .isNull();
    }

    @Test
    void theReferenceTravelsIntoTheEvent() {
        UUID id = transfer("payment-42");

        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE aggregate_id = ? AND event_type = 'TransactionPosted'",
                String.class, id);

        assertThat(JsonPath.<String>read(payload, "$.reference")).isEqualTo("payment-42");
    }

    @Test
    void theDatabaseRefusesABlankReference() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO ledger_transactions (type, reference) VALUES ('TRANSFER', '   ')"))
                .hasStackTraceContaining("ck_transactions_reference_not_blank");
    }
}