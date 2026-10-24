package com.project.ledgercore.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.InsufficientFundsException;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.LedgerService;
import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.support.ConcurrentRunner;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.project.ledgercore.support.LedgerInvariants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TransferConcurrencyIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    TransferService transfers;

    @Autowired
    AccountRepository accounts;

    @Autowired
    CashService cash;

    @Autowired
    LedgerService ledger;

    @Autowired
    LedgerInvariants invariants;

    /** 1,000 random transfers between 10 accounts, 8 at a time: money is conserved and nobody is overdrawn. */
    @Test
    void thousandConcurrentTransfersConserveMoneyAndNeverOverdraw() throws Exception {
        List<Account> pool = fundedAccounts(10, 10_000);
        Random random = new Random(2026);

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            Account from = pool.get(random.nextInt(pool.size()));
            Account to = pool.get(random.nextInt(pool.size()));
            if (to.id().equals(from.id())) {
                to = pool.get((pool.indexOf(from) + 1) % pool.size());
            }
            tasks.add(transferTask(from.id(), to.id(), Money.of(1 + random.nextInt(3_000), "KZT")));
        }

        long succeeded = ConcurrentRunner.runAll(8, tasks).stream().filter(Boolean::booleanValue).count();

        assertThat(succeeded).isPositive();
        assertThat(totalBalance(pool)).isEqualTo(Money.of(100_000, "KZT"));
        for (Account account : pool) {
            assertThat(ledger.balanceOf(account.id()).orElseThrow().isNegative()).isFalse();
        }
        invariants.assertBalanceCacheMatchesEntries();
    }

    /** A to B and B to A at the same time is the classic deadlock. With ordered locking it cannot happen. */
    @Test
    void oppositeTransfersBetweenTwoAccountsNeverDeadlock() throws Exception {
        List<Account> pair = fundedAccounts(2, 100_000);
        Account a = pair.get(0);
        Account b = pair.get(1);

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            tasks.add(i % 2 == 0
                    ? transferTask(a.id(), b.id(), Money.of(1, "KZT"))
                    : transferTask(b.id(), a.id(), Money.of(1, "KZT")));
        }

        List<Boolean> outcomes = ConcurrentRunner.runAll(8, tasks);

        assertThat(outcomes).doesNotContain(false);   // balances are huge, so nothing may be refused
        assertThat(ledger.balanceOf(a.id())).contains(Money.of(100_000, "KZT"));
        assertThat(ledger.balanceOf(b.id())).contains(Money.of(100_000, "KZT"));
        invariants.assertBalanceCacheMatchesEntries();
    }

    /** 100 concurrent transfers of 100 out of one account holding 5,000: exactly 50 may succeed. */
    @Test
    void manyTransfersOutOfOneAccountNeverOverdrawIt() throws Exception {
        Account source = fundedAccounts(1, 5_000).get(0);
        List<Account> targets = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            targets.add(accounts.create(NewAccount.customer("target-" + UUID.randomUUID(), KZT)));
        }

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(transferTask(source.id(), targets.get(i % targets.size()).id(), Money.of(100, "KZT")));
        }

        long succeeded = ConcurrentRunner.runAll(8, tasks).stream().filter(Boolean::booleanValue).count();

        assertThat(succeeded).isEqualTo(50);
        assertThat(ledger.balanceOf(source.id())).contains(Money.zero(KZT));
        assertThat(totalBalance(targets)).isEqualTo(Money.of(5_000, "KZT"));

        invariants.assertBalanceCacheMatchesEntries();
    }

    private Callable<Boolean> transferTask(UUID from, UUID to, Money amount) {
        return () -> {
            try {
                transfers.transfer(from, to, amount);
                return true;
            } catch (InsufficientFundsException e) {
                return false;   // a legitimate refusal; any other exception fails the test
            }
        };
    }

    private List<Account> fundedAccounts(int count, long amountMinor) {
        List<Account> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Account account = accounts.create(NewAccount.customer("concurrent-" + UUID.randomUUID(), KZT));
            cash.deposit(account.id(), Money.of(amountMinor, "KZT"));
            result.add(account);
        }
        return result;
    }

    private Money totalBalance(List<Account> accountList) {
        return accountList.stream()
                .map(account -> ledger.balanceOf(account.id()).orElseThrow())
                .reduce(Money.zero(KZT), Money::plus);
    }
}
