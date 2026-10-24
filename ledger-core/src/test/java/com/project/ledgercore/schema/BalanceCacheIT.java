package com.project.ledgercore.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.support.LedgerFixtures;
import com.project.ledgercore.support.LedgerInvariants;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class BalanceCacheIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    LedgerFixtures fixtures;

    @Autowired
    LedgerInvariants invariants;

    @Test
    void theCachedBalanceFollowsEveryEntry() {
        UUID funding = fixtures.systemAccount("KZT");
        UUID alice = fixtures.customerAccount("KZT");

        commit(funding, alice, 1_000);
        assertThat(cachedBalance(alice)).isEqualTo(1_000);

        commit(alice, funding, 300);
        assertThat(cachedBalance(alice)).isEqualTo(700);
    }

    @Test
    void theDatabaseRefusesToOverdrawACustomerAccountEvenWithHandWrittenSql() {
        UUID funding = fixtures.systemAccount("KZT");
        UUID alice = fixtures.customerAccount("KZT");

        assertThatThrownBy(() -> commit(alice, funding, 100))
                .hasStackTraceContaining("ck_accounts_balance_non_negative");

        assertThat(cachedBalance(alice)).isZero();
    }

    @Test
    void theBanksOwnAccountsMayGoNegative() {
        UUID funding = fixtures.systemAccount("KZT");
        UUID alice = fixtures.customerAccount("KZT");

        commit(funding, alice, 100);

        assertThat(fixtures.balanceOf(funding)).isEqualTo(-100);
    }

    @Test
    void cachedBalancesEqualTheSumOfEntries() {
        UUID funding = fixtures.systemAccount("KZT");
        UUID alice = fixtures.customerAccount("KZT");
        UUID bob = fixtures.customerAccount("KZT");
        commit(funding, alice, 500);
        commit(alice, bob, 120);
        commit(bob, alice, 20);

        invariants.assertBalanceCacheMatchesEntries();
    }

    /** One committed transaction moving {@code amount} from one account to another. */
    private void commit(UUID from, UUID to, long amount) {
        UUID transactionId = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            fixtures.insertTransaction(transactionId);
            fixtures.insertEntry(transactionId, from, -amount, "KZT");
            fixtures.insertEntry(transactionId, to, amount, "KZT");
        });
    }

    private long cachedBalance(UUID accountId) {
        Long balance = jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accountId);
        return balance == null ? 0 : balance;
    }
}