package com.project.ledgercore.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SystemAccountsIT extends AbstractIntegrationTest {

    @Autowired
    SystemAccounts systemAccounts;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void sameAccountIsReturnedEveryTime() {
        Currency kzt = Currency.getInstance("KZT");

        Account first = systemAccounts.cashInBank(kzt);
        Account second = systemAccounts.cashInBank(kzt);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(first.type()).isEqualTo(AccountType.SYSTEM);
        assertThat(first.allowNegative()).isTrue();
        assertThat(first.ownerRef()).isEqualTo(SystemAccounts.CASH_IN_BANK);
    }

    @Test
    void eachCurrencyGetsItsOwnAccount() {
        Account kzt = systemAccounts.cashInBank(Currency.getInstance("KZT"));
        Account usd = systemAccounts.cashInBank(Currency.getInstance("USD"));

        assertThat(usd.id()).isNotEqualTo(kzt.id());
        assertThat(usd.currency().getCurrencyCode()).isEqualTo("USD");
    }

    @Test
    void concurrentCallsCreateExactlyOneAccount() throws Exception {
        Currency chf = Currency.getInstance("CHF");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<UUID> ids = new HashSet<>();
        try {
            List<Future<UUID>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return systemAccounts.cashInBank(chf).id();
                }));
            }
            start.countDown();
            for (Future<UUID> future : futures) {
                ids.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(ids).hasSize(1);
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM accounts WHERE owner_ref = ? AND currency = 'CHF' AND type = 'SYSTEM'",
                Integer.class, SystemAccounts.CASH_IN_BANK);
        assertThat(rows).isEqualTo(1);
    }
}
