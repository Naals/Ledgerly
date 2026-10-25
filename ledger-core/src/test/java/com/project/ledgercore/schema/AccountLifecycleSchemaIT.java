package com.project.ledgercore.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.support.LedgerFixtures;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class AccountLifecycleSchemaIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    LedgerFixtures fixtures;

    @Test
    void anAccountThatStillHoldsMoneyCannotBeClosed() {
        UUID funding = fixtures.systemAccount("KZT");
        UUID alice = fixtures.customerAccount("KZT");
        post(funding, alice, 100);

        assertThatThrownBy(() -> setStatus(alice, "CLOSED"))
                .hasStackTraceContaining("ck_accounts_closed_is_empty");
    }

    @Test
    void aFrozenAccountRefusesNewEntries() {
        UUID funding = fixtures.systemAccount("KZT");
        UUID alice = fixtures.customerAccount("KZT");
        post(funding, alice, 100);
        setStatus(alice, "FROZEN");

        assertThatThrownBy(() -> post(funding, alice, 10))
                .hasStackTraceContaining("account_not_active");
        assertThat(fixtures.balanceOf(alice)).isEqualTo(100);
    }

    @Test
    void anEmptyClosedAccountRefusesNewEntries() {
        UUID funding = fixtures.systemAccount("KZT");
        UUID alice = fixtures.customerAccount("KZT");
        setStatus(alice, "CLOSED");

        assertThatThrownBy(() -> post(funding, alice, 10))
                .hasStackTraceContaining("account_not_active");
    }

    private void setStatus(UUID accountId, String status) {
        jdbc.update("UPDATE accounts SET status = ? WHERE id = ?", status, accountId);
    }

    private void post(UUID from, UUID to, long amount) {
        UUID transactionId = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            fixtures.insertTransaction(transactionId);
            fixtures.insertEntry(transactionId, from, -amount, "KZT");
            fixtures.insertEntry(transactionId, to, amount, "KZT");
        });
    }
}