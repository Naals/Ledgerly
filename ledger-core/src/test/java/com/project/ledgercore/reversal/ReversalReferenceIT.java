package com.project.ledgercore.reversal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.LedgerService;
import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.transfer.TransferService;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ReversalReferenceIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    AccountRepository accounts;

    @Autowired
    CashService cash;

    @Autowired
    TransferService transfers;

    @Autowired
    ReversalService reversals;

    @Autowired
    LedgerService ledger;

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

    @Test
    void aReversalCarriesTheReferenceOfTheTransferItReverses() {
        UUID transfer = transfers.transfer(alice.id(), bob.id(), Money.of(400, "KZT"), "payment-42");

        UUID reversal = reversals.reverse(transfer, "wrong account");

        assertThat(ledger.transaction(reversal).reference()).isEqualTo("payment-42");
        String payload = payloadOf(reversal);
        assertThat(JsonPath.<String>read(payload, "$.reference")).isEqualTo("payment-42");
        assertThat(JsonPath.<String>read(payload, "$.reversesTransactionId")).isEqualTo(transfer.toString());
    }

    @Test
    void aReversalOfATransferWithoutAReferenceHasNone() {
        UUID transfer = transfers.transfer(alice.id(), bob.id(), Money.of(400, "KZT"));

        UUID reversal = reversals.reverse(transfer, null);

        assertThat(ledger.transaction(reversal).reference()).isNull();
    }

    private String payloadOf(UUID transactionId) {
        return jdbc.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE aggregate_id = ? AND event_type = 'TransactionPosted'",
                String.class, transactionId);
    }
}