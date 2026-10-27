package com.project.ledgercore.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.support.LedgerFixtures;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class ReversalSchemaIT extends AbstractIntegrationTest {

    @Autowired
    TransactionTemplate tx;

    @Autowired
    LedgerFixtures fixtures;

    @Autowired
    JdbcTemplate jdbc;

    private UUID funding;
    private UUID alice;
    private UUID bob;
    private UUID transfer;   // alice pays bob 400

    @BeforeEach
    void postATransfer() {
        funding = fixtures.systemAccount("KZT");
        alice = fixtures.customerAccount("KZT");
        bob = fixtures.customerAccount("KZT");
        commit(funding, alice, 1_000);
        transfer = commit(alice, bob, 400);
    }

    @Test
    void anExactReversalIsAccepted() {
        commitReversal(transfer, alice, 400, bob, -400);

        assertThat(fixtures.balanceOf(alice)).isEqualTo(1_000);
        assertThat(fixtures.balanceOf(bob)).isZero();
    }

    @Test
    void aTransactionCanBeReversedOnlyOnce() {
        commitReversal(transfer, alice, 400, bob, -400);

        assertThatThrownBy(() -> commitReversal(transfer, alice, 400, bob, -400))
                .hasStackTraceContaining("uq_transactions_reverses");
    }

    @Test
    void aReversalCannotBeReversed() {
        UUID reversal = commitReversal(transfer, alice, 400, bob, -400);

        assertThatThrownBy(() -> commitReversal(reversal, alice, -400, bob, 400))
                .hasStackTraceContaining("reversal_of_reversal");
    }

    @Test
    void aReversalWithAWrongAmountIsRefused() {
        assertThatThrownBy(() -> commitReversal(transfer, alice, 399, bob, -399))
                .hasStackTraceContaining("reversal_not_exact");
    }

    @Test
    void aBalancedReversalOnOtherAccountsIsRefused() {
        assertThatThrownBy(() -> commitReversal(transfer, funding, 400, bob, -400))
                .hasStackTraceContaining("reversal_not_exact");
    }

    @Test
    void theTypeAndTheOriginalMustAgree() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ledger_transactions (type) VALUES ('REVERSAL')"))
                .hasStackTraceContaining("ck_reversal_names_its_original");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO ledger_transactions (type, reverses_transaction_id) VALUES ('TRANSFER', ?)", transfer))
                .hasStackTraceContaining("ck_reversal_names_its_original");
    }

    private UUID commit(UUID from, UUID to, long amount) {
        UUID transactionId = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            fixtures.insertTransaction(transactionId);
            fixtures.insertEntry(transactionId, from, -amount, "KZT");
            fixtures.insertEntry(transactionId, to, amount, "KZT");
        });
        return transactionId;
    }

    private UUID commitReversal(UUID original, UUID firstAccount, long firstAmount,
                                UUID secondAccount, long secondAmount) {
        UUID reversalId = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            fixtures.insertReversal(reversalId, original);
            fixtures.insertEntry(reversalId, firstAccount, firstAmount, "KZT");
            fixtures.insertEntry(reversalId, secondAccount, secondAmount, "KZT");
        });
        return reversalId;
    }
}