package com.project.ledgercore.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.support.ConcurrentRunner;
import com.project.ledgercore.support.LedgerInvariants;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PostingConcurrencyIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    LedgerService ledger;

    @Autowired
    AccountRepository accounts;

    @Autowired
    CashService cash;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LedgerInvariants invariants;

    /**
     * Postings written straight to the ledger, A to B and B to A at the same time, with no explicit locks.
     * The balance trigger locks each account as its entry is inserted, so without a fixed insertion order
     * these two directions would deadlock.
     */
    @Test
    void oppositePostingsWithoutExplicitLocksNeverDeadlock() throws Exception {
        Account a = fundedAccount();
        Account b = fundedAccount();

        List<Callable<Boolean>> postings = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            Account from = i % 2 == 0 ? a : b;
            Account to = i % 2 == 0 ? b : a;
            postings.add(() -> {
                ledger.post(new PostingRequest(TransactionType.TRANSFER, "raw posting", List.of(
                        PostingLine.debit(from.id(), Money.of(1, "KZT")),
                        PostingLine.credit(to.id(), Money.of(1, "KZT")))));
                return true;
            });
        }

        ConcurrentRunner.runAll(8, postings);   // a deadlock would surface as an exception here

        assertThat(ledger.balanceOf(a.id())).contains(Money.of(100_000, "KZT"));
        assertThat(ledger.balanceOf(b.id())).contains(Money.of(100_000, "KZT"));
        invariants.assertBalanceCacheMatchesEntries();
    }

    @Test
    void entriesAreInsertedInAscendingAccountIdOrder() {
        Account a = fundedAccount();
        Account b = fundedAccount();
        Account c = fundedAccount();

        UUID transactionId = ledger.post(new PostingRequest(TransactionType.TRANSFER, "ordering", List.of(
                PostingLine.debit(c.id(), Money.of(10, "KZT")),
                PostingLine.credit(a.id(), Money.of(4, "KZT")),
                PostingLine.credit(b.id(), Money.of(6, "KZT")))));

        List<String> accountsInInsertionOrder = jdbc.queryForList(
                "SELECT account_id::text FROM ledger_entries WHERE transaction_id = ? ORDER BY id",
                String.class, transactionId);

        assertThat(accountsInInsertionOrder).hasSize(3);
        assertThat(accountsInInsertionOrder).isSortedAccordingTo(Comparator.naturalOrder());
    }

    private Account fundedAccount() {
        Account account = accounts.create(NewAccount.customer("posting-" + UUID.randomUUID(), KZT));
        cash.deposit(account.id(), Money.of(100_000, "KZT"));
        return account;
    }
}