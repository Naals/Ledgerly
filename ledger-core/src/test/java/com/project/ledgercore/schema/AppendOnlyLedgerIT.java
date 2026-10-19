package com.project.ledgercore.schema;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.support.LedgerFixtures;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class AppendOnlyLedgerIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    LedgerFixtures fixtures;

    private UUID committedTransactionId;

    @BeforeEach
    void commitOneTransfer() {
        UUID from = fixtures.systemAccount("KZT");
        UUID to = fixtures.customerAccount("KZT");
        committedTransactionId = UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            fixtures.insertTransaction(committedTransactionId);
            fixtures.insertEntry(committedTransactionId, from, -100, "KZT");
            fixtures.insertEntry(committedTransactionId, to, 100, "KZT");
        });
    }

    @Test
    void entriesCannotBeUpdated() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ledger_entries SET amount = 1 WHERE transaction_id = ?", committedTransactionId))
                .hasStackTraceContaining("append-only");
    }

    @Test
    void entriesCannotBeDeleted() {
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM ledger_entries WHERE transaction_id = ?", committedTransactionId))
                .hasStackTraceContaining("append-only");
    }

    @Test
    void transactionsCannotBeUpdated() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ledger_transactions SET description = 'edited' WHERE id = ?", committedTransactionId))
                .hasStackTraceContaining("append-only");
    }

    @Test
    void transactionsCannotBeDeleted() {
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM ledger_transactions WHERE id = ?", committedTransactionId))
                .hasStackTraceContaining("append-only");
    }
}
