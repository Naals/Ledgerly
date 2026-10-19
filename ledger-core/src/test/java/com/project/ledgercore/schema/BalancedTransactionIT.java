package com.project.ledgercore.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.support.LedgerFixtures;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class BalancedTransactionIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    LedgerFixtures fixtures;

    @Test
    void unbalancedTransactionIsRejectedAtCommitAndLeavesNothingBehind() {
        UUID from = fixtures.customerAccount("KZT");
        UUID to = fixtures.customerAccount("KZT");
        UUID txId = UUID.randomUUID();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            fixtures.insertTransaction(txId);
            fixtures.insertEntry(txId, from, -100, "KZT");
            fixtures.insertEntry(txId, to, 50, "KZT");
        })).hasStackTraceContaining("unbalanced");

        assertThat(count("ledger_entries", "transaction_id", txId)).isZero();
        assertThat(count("ledger_transactions", "id", txId)).isZero();
    }

    @Test
    void splitAcrossThreeAccountsIsAcceptedWhenItBalances() {
        UUID funding = fixtures.systemAccount("KZT");
        UUID merchant = fixtures.customerAccount("KZT");
        UUID fees = fixtures.systemAccount("KZT");
        UUID txId = UUID.randomUUID();

        assertThatCode(() -> tx.executeWithoutResult(status -> {
            fixtures.insertTransaction(txId);
            fixtures.insertEntry(txId, funding, -1_000, "KZT");
            fixtures.insertEntry(txId, merchant, 900, "KZT");
            fixtures.insertEntry(txId, fees, 100, "KZT");
        })).doesNotThrowAnyException();
    }

    @Test
    void currenciesCannotOffsetEachOther() {
        UUID usdFunding = fixtures.systemAccount("USD");
        UUID kztCustomer = fixtures.customerAccount("KZT");
        UUID txId = UUID.randomUUID();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            fixtures.insertTransaction(txId);
            fixtures.insertEntry(txId, usdFunding, -100, "USD");
            fixtures.insertEntry(txId, kztCustomer, 100, "KZT");
        })).hasStackTraceContaining("unbalanced");
    }

    @Test
    void oneTransactionMayCarryTwoCurrenciesIfEachBalances() {
        UUID kztFunding = fixtures.systemAccount("KZT");
        UUID kztCustomer = fixtures.customerAccount("KZT");
        UUID usdFunding = fixtures.systemAccount("USD");
        UUID usdCustomer = fixtures.customerAccount("USD");
        UUID txId = UUID.randomUUID();

        assertThatCode(() -> tx.executeWithoutResult(status -> {
            fixtures.insertTransaction(txId);
            fixtures.insertEntry(txId, kztFunding, -100, "KZT");
            fixtures.insertEntry(txId, kztCustomer, 100, "KZT");
            fixtures.insertEntry(txId, usdFunding, -50, "USD");
            fixtures.insertEntry(txId, usdCustomer, 50, "USD");
        })).doesNotThrowAnyException();
    }

    private int count(String table, String column, UUID id) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, id);
        return n == null ? 0 : n;
    }
}
