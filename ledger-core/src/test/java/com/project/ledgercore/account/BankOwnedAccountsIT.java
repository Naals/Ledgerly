package com.project.ledgercore.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.LedgerService;
import com.project.ledgercore.ledger.PostingLine;
import com.project.ledgercore.ledger.PostingRequest;
import com.project.ledgercore.ledger.TransactionType;
import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class BankOwnedAccountsIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    AccountRepository accounts;

    @Autowired
    SystemAccounts systemAccounts;

    @Autowired
    CashService cash;

    @Autowired
    LedgerService ledger;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Test
    void theBanksOwnAccountsAreNotCached() {
        Account alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        Account cashInBank = systemAccounts.cashInBank(KZT);

        cash.deposit(alice.id(), Money.of(700, "KZT"));

        Long cached = jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, cashInBank.id());
        assertThat(cached).isZero();
    }

    @Test
    void theirBalanceIsSummedFromTheEntries() {
        Account funding = accounts.create(NewAccount.system("funding-" + UUID.randomUUID(), KZT));
        Account alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));

        ledger.post(new PostingRequest(TransactionType.DEPOSIT, "test", List.of(
                PostingLine.debit(funding.id(), Money.of(700, "KZT")),
                PostingLine.credit(alice.id(), Money.of(700, "KZT")))));

        assertThat(ledger.balanceOf(funding.id())).contains(Money.of(-700, "KZT"));
    }

    /** Fails if cash-in-bank is ever locked per operation again (as in V7): the second deposit would have to wait. */
    @Test
    void depositsToDifferentAccountsDoNotWaitForEachOther() throws Exception {
        Account x = accounts.create(NewAccount.customer("x-" + UUID.randomUUID(), KZT));
        Account y = accounts.create(NewAccount.customer("y-" + UUID.randomUUID(), KZT));
        systemAccounts.cashInBank(KZT);   // make sure it exists before the transactions below start

        CountDownLatch firstHasPosted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> tx.executeWithoutResult(status -> {
                cash.deposit(x.id(), Money.of(100, "KZT"));   // joins this transaction, which stays open
                firstHasPosted.countDown();
                awaitQuietly(release);
            }));
            assertThat(firstHasPosted.await(30, TimeUnit.SECONDS)).isTrue();

            Future<UUID> second = pool.submit(() -> cash.deposit(y.id(), Money.of(200, "KZT")));
            assertThat(second.get(10, TimeUnit.SECONDS)).isNotNull();   // times out if cash-in-bank is a hot row

            release.countDown();
            first.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}