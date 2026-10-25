package com.project.ledgercore.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.support.AbstractIntegrationTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class PointInTimeBalanceIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountRepository accounts;

    @Autowired
    SystemAccounts systemAccounts;

    @Autowired
    CashService cash;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theBalanceAsOfEachMoment() throws Exception {
        Account alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        OffsetDateTime beforeAnything = databaseNow();
        pause();
        cash.deposit(alice.id(), Money.of(1_000, "KZT"));
        OffsetDateTime afterFirst = databaseNow();
        pause();
        cash.deposit(alice.id(), Money.of(500, "KZT"));
        OffsetDateTime afterSecond = databaseNow();
        pause();
        cash.withdraw(alice.id(), Money.of(300, "KZT"));
        OffsetDateTime afterThird = databaseNow();

        assertBalanceAt(alice, beforeAnything, 0);
        assertBalanceAt(alice, afterFirst, 1_000);
        assertBalanceAt(alice, afterSecond, 1_500);
        assertBalanceAt(alice, afterThird, 1_200);
    }

    @Test
    void aTimeInTheFutureGivesTheCurrentBalance() throws Exception {
        Account alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        cash.deposit(alice.id(), Money.of(700, "KZT"));

        assertBalanceAt(alice, databaseNow().plusYears(1), 700);
    }

    @Test
    void beforeTheAccountExistedTheBalanceIsZero() throws Exception {
        Account alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        cash.deposit(alice.id(), Money.of(700, "KZT"));

        assertBalanceAt(alice, OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC), 0);
    }

    @Test
    void badRequestsAreRejected() throws Exception {
        Account alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        Account cashInBank = systemAccounts.cashInBank(KZT);

        mvc.perform(get("/api/v1/accounts/{id}/balance", alice.id()).param("at", "yesterday"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/accounts/{id}/balance", cashInBank.id()).param("at", "2026-01-01T00:00:00Z"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:not-a-customer-account"));
    }

    private void assertBalanceAt(Account account, OffsetDateTime at, long expectedMinor) throws Exception {
        mvc.perform(get("/api/v1/accounts/{id}/balance", account.id())
                        .param("at", at.withOffsetSameInstant(ZoneOffset.UTC).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountMinor").value(expectedMinor))
                .andExpect(jsonPath("$.asOf").isNotEmpty());
    }

    /** The database's own clock, the same one that stamps entries, so the test never compares two clocks. */
    private OffsetDateTime databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
    }

    private static void pause() throws InterruptedException {
        Thread.sleep(25);
    }
}