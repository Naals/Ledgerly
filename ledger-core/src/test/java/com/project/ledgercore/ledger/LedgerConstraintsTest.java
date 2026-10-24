package com.project.ledgercore.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LedgerConstraintsTest {

    @Test
    void recognisesTheOverdraftConstraintInTheMessage() {
        RuntimeException error = new RuntimeException("ERROR: new row for relation \"accounts\" violates check "
                + "constraint \"ck_accounts_balance_non_negative\"");

        assertThat(LedgerConstraints.isOverdraftViolation(error)).isTrue();
    }

    @Test
    void recognisesItInsideACauseChain() {
        RuntimeException root = new RuntimeException("violates check constraint \"ck_accounts_balance_non_negative\"");
        RuntimeException wrapped = new IllegalStateException("could not execute statement",
                new RuntimeException("PreparedStatementCallback; SQL []", root));

        assertThat(LedgerConstraints.isOverdraftViolation(wrapped)).isTrue();
    }

    @Test
    void ignoresOtherFailures() {
        assertThat(LedgerConstraints.isOverdraftViolation(
                new RuntimeException("duplicate key value violates unique constraint"))).isFalse();
        assertThat(LedgerConstraints.isOverdraftViolation(new RuntimeException())).isFalse();
    }
}