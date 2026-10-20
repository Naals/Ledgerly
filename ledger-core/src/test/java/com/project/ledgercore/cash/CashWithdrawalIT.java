package com.project.ledgercore.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountNotActiveException;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.account.SystemAccounts;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.LedgerService;
import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CashWithdrawalIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    CashService cash;

    @Autowired
    AccountRepository accounts;

    @Autowired
    SystemAccounts systemAccounts;

    @Autowired
    LedgerService ledger;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void withdrawalDebitsTheCustomerAndCreditsCashInBank() {
        Account alice = accounts.create(NewAccount.customer("alice", KZT));
        Account cashInBank = systemAccounts.cashInBank(KZT);
        Money cashBefore = ledger.balanceOf(cashInBank.id()).orElseThrow();

        cash.deposit(alice.id(), Money.of(1_000, "KZT"));
        cash.withdraw(alice.id(), Money.of(400, "KZT"));

        assertThat(ledger.balanceOf(alice.id())).contains(Money.of(600, "KZT"));
        assertThat(ledger.balanceOf(cashInBank.id())).contains(cashBefore.minus(Money.of(600, "KZT")));
    }

    @Test
    void withdrawalBeyondTheBalanceIsRejectedAndChangesNothing() {
        Account alice = accounts.create(NewAccount.customer("alice", KZT));
        cash.deposit(alice.id(), Money.of(500, "KZT"));

        assertThatThrownBy(() -> cash.withdraw(alice.id(), Money.of(501, "KZT")))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("5.00 KZT")
                .hasMessageContaining("5.01 KZT");

        assertThat(ledger.balanceOf(alice.id())).contains(Money.of(500, "KZT"));
    }

    @Test
    void accountCanBeEmptiedExactly() {
        Account alice = accounts.create(NewAccount.customer("alice", KZT));
        cash.deposit(alice.id(), Money.of(300, "KZT"));

        cash.withdraw(alice.id(), Money.of(300, "KZT"));

        assertThat(ledger.balanceOf(alice.id())).contains(Money.zero(KZT));
    }

    @Test
    void frozenAccountCannotWithdraw() {
        Account alice = accounts.create(NewAccount.customer("alice", KZT));
        cash.deposit(alice.id(), Money.of(300, "KZT"));
        jdbc.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?", alice.id());

        assertThatThrownBy(() -> cash.withdraw(alice.id(), Money.of(100, "KZT")))
                .isInstanceOf(AccountNotActiveException.class);
    }

    /** 20 concurrent withdrawals of 100 against a balance of 1,000: exactly 10 may succeed. */
    @Test
    void concurrentWithdrawalsNeverOverdrawTheAccount() throws Exception {
        Account alice = accounts.create(NewAccount.customer("alice-concurrent", KZT));
        cash.deposit(alice.id(), Money.of(1_000, "KZT"));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        int succeeded = 0;
        try {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                attempts.add(pool.submit(() -> {
                    start.await();
                    try {
                        cash.withdraw(alice.id(), Money.of(100, "KZT"));
                        return true;
                    } catch (InsufficientFundsException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get(30, TimeUnit.SECONDS)) {
                    succeeded++;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded).isEqualTo(10);
        assertThat(ledger.balanceOf(alice.id())).contains(Money.zero(KZT));
    }
}