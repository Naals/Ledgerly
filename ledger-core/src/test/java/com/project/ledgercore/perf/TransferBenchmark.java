package com.project.ledgercore.perf;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.account.SystemAccounts;
import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.LedgerService;
import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.support.ConcurrentRunner;
import com.project.ledgercore.support.LatencyStats;
import com.project.ledgercore.transfer.TransferService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * On-demand benchmark. It is NOT run by {@code mvn verify}: the class name matches no test pattern.
 * Run it with Docker running:
 *
 * <pre>mvn -pl ledger-core -Dtest=TransferBenchmark -Dsurefire.failIfNoSpecifiedTests=false test</pre>
 *
 * It prints a markdown table and writes it to ledger-core/target/benchmark-report.md.
 * Absolute numbers depend on the machine: compare runs made on the SAME machine, before and after a change.
 */
class TransferBenchmark extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");
    private static final int THREADS = 8;

    @Autowired
    TransferService transfers;

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
    void measure() throws Exception {
        List<Account> wide = fundedAccounts(200, 1_000_000);
        List<Account> narrow = fundedAccounts(5, 1_000_000);
        run(THREADS, randomTransfers(wide, 500, 0));   // warm-up: JIT, connection pool, caches

        StringBuilder report = new StringBuilder();
        report.append("| scenario | operations | ops/s | mean ms | p50 ms | p95 ms | p99 ms | max ms |\n");
        report.append("|---|---|---|---|---|---|---|---|\n");
        report.append(row("transfers between 200 accounts (low contention)",
                run(THREADS, randomTransfers(wide, 4_000, 1))));
        report.append(row("transfers between 5 accounts (high contention)",
                run(THREADS, randomTransfers(narrow, 2_000, 2))));
        report.append(row("cash deposits into 200 accounts (all post against one cash account)",
                run(THREADS, deposits(wide, 4_000, 3))));
        for (int history : new int[] {0, 1_000, 10_000, 100_000}) {
            Account account = accountWithHistory(history);
            report.append(row("balance read, account with " + history + " entries",
                    run(1, balanceReads(account, 300))));
        }

        System.out.println("\n" + report);
        Files.writeString(Path.of("target", "benchmark-report.md"), report.toString());
    }

    private Measurement run(int threads, List<Callable<Object>> operations) throws Exception {
        List<Callable<Long>> timed = new ArrayList<>();
        for (Callable<Object> operation : operations) {
            timed.add(() -> {
                long start = System.nanoTime();
                operation.call();
                return System.nanoTime() - start;
            });
        }
        long start = System.nanoTime();
        List<Long> nanos = ConcurrentRunner.runAll(threads, timed);
        return new Measurement(LatencyStats.of(nanos), System.nanoTime() - start);
    }

    private List<Callable<Object>> randomTransfers(List<Account> pool, int count, long seed) {
        Random random = new Random(seed);
        List<Callable<Object>> operations = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Account from = pool.get(random.nextInt(pool.size()));
            Account to = pool.get((pool.indexOf(from) + 1 + random.nextInt(pool.size() - 1)) % pool.size());
            Money amount = Money.of(1 + random.nextInt(50), "KZT");
            operations.add(() -> transfers.transfer(from.id(), to.id(), amount));
        }
        return operations;
    }

    private List<Callable<Object>> deposits(List<Account> pool, int count, long seed) {
        Random random = new Random(seed);
        List<Callable<Object>> operations = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Account account = pool.get(random.nextInt(pool.size()));
            Money amount = Money.of(1 + random.nextInt(50), "KZT");
            operations.add(() -> cash.deposit(account.id(), amount));
        }
        return operations;
    }

    private List<Callable<Object>> balanceReads(Account account, int count) {
        List<Callable<Object>> operations = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            operations.add(() -> ledger.balanceOf(account.id()));
        }
        return operations;
    }

    private List<Account> fundedAccounts(int count, long amountMinor) {
        List<Account> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Account account = accounts.create(NewAccount.customer("bench-" + UUID.randomUUID(), KZT));
            cash.deposit(account.id(), Money.of(amountMinor, "KZT"));
            result.add(account);
        }
        return result;
    }

    /** An account with the given number of entries, written in batches of 1,000 transactions. */
    private Account accountWithHistory(int entries) {
        Account account = accounts.create(NewAccount.customer("history-" + UUID.randomUUID(), KZT));
        UUID cashInBank = systemAccounts.cashInBank(KZT).id();
        for (int done = 0; done < entries; done += 1_000) {
            jdbc.update("""
                    WITH t AS (INSERT INTO ledger_transactions (type)
                               SELECT 'DEPOSIT' FROM generate_series(1, ?) RETURNING id)
                    INSERT INTO ledger_entries (transaction_id, account_id, amount, currency)
                    SELECT id, ?::uuid, -100, 'KZT' FROM t
                    UNION ALL
                    SELECT id, ?::uuid, 100, 'KZT' FROM t
                    """, Math.min(1_000, entries - done), cashInBank.toString(), account.id().toString());
        }
        return account;
    }

    private static String row(String scenario, Measurement measurement) {
        LatencyStats stats = measurement.stats();
        return String.format(Locale.ROOT, "| %s | %d | %.0f | %.2f | %.2f | %.2f | %.2f | %.2f |%n",
                scenario, stats.count(), measurement.opsPerSecond(), stats.meanMillis(),
                stats.percentileMillis(50), stats.percentileMillis(95), stats.percentileMillis(99),
                stats.maxMillis());
    }

    private record Measurement(LatencyStats stats, long wallNanos) {

        double opsPerSecond() {
            return stats.count() / (wallNanos / 1_000_000_000.0);
        }
    }
}