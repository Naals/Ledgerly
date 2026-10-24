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
import com.project.ledgercore.support.LedgerInvariants;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MixedWorkloadConcurrencyIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");
    private static final int ACCOUNTS = 20;
    private static final long INITIAL_BALANCE = 10_000;

    @Autowired
    TransferService transfers;

    @Autowired
    CashService cash;

    @Autowired
    AccountRepository accounts;

    @Autowired
    LedgerService ledger;

    @Autowired
    LedgerInvariants invariants;

    /** Deposits, withdrawals and transfers all at once: money adds up, nobody is overdrawn, the cache is exact. */
    @Test
    void mixedConcurrentOperationsKeepEveryInvariant() throws Exception {
        List<Account> pool = fundedAccounts();
        Random random = new Random(7);
        AtomicLong deposited = new AtomicLong();
        AtomicLong withdrawn = new AtomicLong();

        List<Callable<Boolean>> operations = new ArrayList<>();
        for (int i = 0; i < 900; i++) {
            Account account = pool.get(random.nextInt(pool.size()));
            Account other = pool.get((pool.indexOf(account) + 1 + random.nextInt(pool.size() - 1)) % pool.size());
            long amount = 1 + random.nextInt(2_000);
            switch (i % 3) {
                case 0 -> operations.add(() -> {
                    cash.deposit(account.id(), Money.of(amount, "KZT"));
                    deposited.addAndGet(amount);
                    return true;
                });
                case 1 -> operations.add(() -> {
                    try {
                        cash.withdraw(account.id(), Money.of(amount, "KZT"));
                        withdrawn.addAndGet(amount);
                        return true;
                    } catch (InsufficientFundsException e) {
                        return false;
                    }
                });
                default -> operations.add(() -> {
                    try {
                        transfers.transfer(account.id(), other.id(), Money.of(amount, "KZT"));
                        return true;
                    } catch (InsufficientFundsException e) {
                        return false;
                    }
                });
            }
        }

        ConcurrentRunner.runAll(8, operations);

        long total = pool.stream().mapToLong(a -> ledger.balanceOf(a.id()).orElseThrow().minorUnits()).sum();
        assertThat(total).isEqualTo(ACCOUNTS * INITIAL_BALANCE + deposited.get() - withdrawn.get());
        for (Account account : pool) {
            assertThat(ledger.balanceOf(account.id()).orElseThrow().isNegative()).isFalse();
        }
        invariants.assertBalanceCacheMatchesEntries();
    }

    private List<Account> fundedAccounts() {
        List<Account> result = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            Account account = accounts.create(NewAccount.customer("mixed-" + UUID.randomUUID(), KZT));
            cash.deposit(account.id(), Money.of(INITIAL_BALANCE, "KZT"));
            result.add(account);
        }
        return result;
    }
}