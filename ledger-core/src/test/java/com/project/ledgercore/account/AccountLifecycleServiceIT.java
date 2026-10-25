package com.project.ledgercore.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.BusinessRuleException;
import com.project.ledgercore.common.ConflictException;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.LedgerService;
import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class AccountLifecycleServiceIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    AccountLifecycleService lifecycle;

    @Autowired
    AccountRepository accounts;

    @Autowired
    SystemAccounts systemAccounts;

    @Autowired
    CashService cash;

    @Autowired
    LedgerService ledger;

    @Autowired
    TransactionTemplate tx;

    @Test
    void anAccountCanBeFrozenAndUnfrozen() {
        Account alice = newAccount();

        assertThat(lifecycle.freeze(alice.id()).status()).isEqualTo(AccountStatus.FROZEN);
        assertThatThrownBy(() -> cash.deposit(alice.id(), Money.of(100, "KZT")))
                .isInstanceOf(AccountNotActiveException.class);

        assertThat(lifecycle.unfreeze(alice.id()).status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(cash.deposit(alice.id(), Money.of(100, "KZT"))).isNotNull();
    }

    @Test
    void closingNeedsAnEmptyAccount() {
        Account alice = newAccount();
        cash.deposit(alice.id(), Money.of(100, "KZT"));

        assertThatThrownBy(() -> lifecycle.close(alice.id()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("account-not-empty");

        cash.withdraw(alice.id(), Money.of(100, "KZT"));
        assertThat(lifecycle.close(alice.id()).status()).isEqualTo(AccountStatus.CLOSED);
    }

    @Test
    void aClosedAccountIsFinal() {
        Account alice = newAccount();
        lifecycle.close(alice.id());

        assertThatThrownBy(() -> lifecycle.unfreeze(alice.id())).isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(() -> lifecycle.freeze(alice.id())).isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(() -> cash.deposit(alice.id(), Money.of(100, "KZT")))
                .isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    void repeatingARequestChangesNothing() {
        Account alice = newAccount();

        lifecycle.freeze(alice.id());
        Account again = lifecycle.freeze(alice.id());

        assertThat(again.status()).isEqualTo(AccountStatus.FROZEN);
    }

    @Test
    void bankOwnedAccountsCannotChangeStatus() {
        Account cashInBank = systemAccounts.cashInBank(KZT);

        assertThatThrownBy(() -> lifecycle.freeze(cashInBank.id()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not a customer account");
    }

    @Test
    void unknownAccountIsNotFound() {
        assertThatThrownBy(() -> lifecycle.freeze(UUID.randomUUID()))
                .isInstanceOf(AccountNotFoundException.class);
    }

    /**
     * A deposit that starts while a closure is still uncommitted passes its own status check (it still sees
     * ACTIVE), then waits for the account row. When the closure commits, the database refuses the deposit.
     */
    @Test
    void aDepositRacingWithAClosureIsRefusedByTheDatabase() throws Exception {
        Account alice = newAccount();
        CountDownLatch closed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> closing = pool.submit(() -> tx.executeWithoutResult(status -> {
                lifecycle.close(alice.id());      // joins this transaction: the account row stays locked
                closed.countDown();
                awaitQuietly(release);
            }));
            assertThat(closed.await(30, TimeUnit.SECONDS)).isTrue();

            Future<UUID> deposit = pool.submit(() -> cash.deposit(alice.id(), Money.of(100, "KZT")));
            Thread.sleep(500);                    // the deposit is now waiting for the account row
            release.countDown();
            closing.get(30, TimeUnit.SECONDS);

            assertThatThrownBy(() -> deposit.get(30, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ConflictException.class);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(accounts.findById(alice.id())).get().extracting(Account::status).isEqualTo(AccountStatus.CLOSED);
        assertThat(ledger.balanceOf(alice.id())).contains(Money.zero(KZT));
    }

    private Account newAccount() {
        return accounts.create(NewAccount.customer("lifecycle-" + UUID.randomUUID(), KZT));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}