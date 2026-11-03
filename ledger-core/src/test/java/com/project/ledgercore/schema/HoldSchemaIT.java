package com.project.ledgercore.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.support.LedgerFixtures;
import com.project.ledgercore.support.LedgerInvariants;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** The hold rules, checked against the database alone with hand-written SQL. */
class HoldSchemaIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    LedgerFixtures fixtures;

    @Autowired
    LedgerInvariants invariants;

    private UUID cash;
    private UUID alice;
    private UUID bob;

    @BeforeEach
    void openAccounts() {
        cash = fixtures.systemAccount("KZT");
        alice = fixtures.customerAccount("KZT");
        bob = fixtures.customerAccount("KZT");
        move(cash, alice, 1_000);
    }

    @Test
    void aHoldReservesFundsWithinTheAvailableBalance() {
        fixtures.insertHold(alice, bob, 300, "KZT", 3_600);

        assertThat(held(alice)).isEqualTo(300);
        assertThat(balance(alice)).isEqualTo(1_000);
        invariants.assertHeldMatchesActiveHolds();
    }

    @Test
    void aHoldBeyondTheAvailableBalanceIsRefused() {
        fixtures.insertHold(alice, bob, 300, "KZT", 3_600);

        assertThatThrownBy(() -> fixtures.insertHold(alice, bob, 701, "KZT", 3_600))
                .hasStackTraceContaining("ck_accounts_available_non_negative");

        assertThat(held(alice)).isEqualTo(300);
    }

    @Test
    void reservedFundsCannotBeSpentUntilTheHoldIsReleased() {
        UUID hold = fixtures.insertHold(alice, bob, 1_000, "KZT", 3_600);

        assertThatThrownBy(() -> move(alice, bob, 1))
                .hasStackTraceContaining("ck_accounts_available_non_negative");

        setStatus(hold, "RELEASED");
        move(alice, bob, 700);

        assertThat(balance(alice)).isEqualTo(300);
    }

    @Test
    void aCaptureMustFreeTheHoldBeforePostingTheDebit() {
        UUID hold = fixtures.insertHold(alice, bob, 600, "KZT", 3_600);

        // debit first: the 600 are still reserved, so the database refuses
        assertThatThrownBy(() -> move(alice, bob, 600))
                .hasStackTraceContaining("ck_accounts_available_non_negative");

        // capture first, then debit, in one transaction: allowed
        UUID transaction = tx.execute(status -> {
            jdbc.update("UPDATE holds SET status = 'CAPTURED', captured_minor = 600 WHERE id = ?", hold);
            UUID id = UUID.randomUUID();
            fixtures.insertTransaction(id);
            fixtures.insertEntry(id, alice, -600, "KZT");
            fixtures.insertEntry(id, bob, 600, "KZT");
            return id;
        });
        jdbc.update("UPDATE holds SET ledger_transaction_id = ? WHERE id = ?", transaction, hold);

        assertThat(balance(alice)).isEqualTo(400);
        assertThat(held(alice)).isZero();
        assertThat(balance(bob)).isEqualTo(600);
        invariants.assertHeldMatchesActiveHolds();
    }

    @Test
    void theTermsOfAnActiveHoldNeverChangeAndAFinishedHoldNeverMovesAgain() {
        UUID hold = fixtures.insertHold(alice, bob, 300, "KZT", 3_600);

        assertThatThrownBy(() -> jdbc.update("UPDATE holds SET amount_minor = 1 WHERE id = ?", hold))
                .hasStackTraceContaining("hold_terms_immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE holds SET to_account_id = ? WHERE id = ?", alice, hold))
                .hasStackTraceContaining("hold_terms_immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE holds SET status = 'CAPTURED' WHERE id = ?", hold))
                .hasStackTraceContaining("ck_hold_captured_amount");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE holds SET status = 'CAPTURED', captured_minor = 301 WHERE id = ?", hold))
                .hasStackTraceContaining("ck_hold_capture_within_hold");

        setStatus(hold, "RELEASED");

        assertThatThrownBy(() -> setStatus(hold, "ACTIVE")).hasStackTraceContaining("hold_not_active");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE holds SET status = 'CAPTURED', captured_minor = 100 WHERE id = ?", hold))
                .hasStackTraceContaining("hold_not_active");
    }

    @Test
    void aHoldCanOnlyBeCapturedBeforeItExpiresAndExpiredAfterIt() {
        UUID late = fixtures.insertHold(alice, bob, 400, "KZT", -5);
        UUID early = fixtures.insertHold(alice, bob, 100, "KZT", 3_600);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE holds SET status = 'CAPTURED', captured_minor = 400 WHERE id = ?", late))
                .hasStackTraceContaining("hold_expired");
        assertThatThrownBy(() -> setStatus(early, "EXPIRED")).hasStackTraceContaining("hold_not_due");

        setStatus(late, "EXPIRED");

        assertThat(held(alice)).isEqualTo(100);
        invariants.assertHeldMatchesActiveHolds();
    }

    @Test
    void onlyActiveCustomerAccountsCanHoldAndAccountsWithHoldsCannotBeClosed() {
        move(cash, bob, 100);
        jdbc.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?", bob);

        assertThatThrownBy(() -> fixtures.insertHold(bob, alice, 10, "KZT", 3_600))
                .hasStackTraceContaining("account_not_active");
        assertThatThrownBy(() -> fixtures.insertHold(cash, alice, 10, "KZT", 3_600))
                .hasStackTraceContaining("hold_on_bank_account");

        fixtures.insertHold(alice, bob, 200, "KZT", 3_600);
        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET status = 'CLOSED' WHERE id = ?", alice))
                .hasStackTraceContaining("ck_accounts_closed_is_empty");
    }

    @Test
    void holdsAreNeverDeleted() {
        UUID hold = fixtures.insertHold(alice, bob, 300, "KZT", 3_600);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM holds WHERE id = ?", hold))
                .hasStackTraceContaining("append-only");
    }

    /** One balanced transaction, committed on its own. */
    private UUID move(UUID from, UUID to, long amount) {
        return tx.execute(status -> {
            UUID id = UUID.randomUUID();
            fixtures.insertTransaction(id);
            fixtures.insertEntry(id, from, -amount, "KZT");
            fixtures.insertEntry(id, to, amount, "KZT");
            return id;
        });
    }

    private void setStatus(UUID hold, String status) {
        jdbc.update("UPDATE holds SET status = ? WHERE id = ?", status, hold);
    }

    private long held(UUID account) {
        return jdbc.queryForObject("SELECT held FROM accounts WHERE id = ?", Long.class, account);
    }

    private long balance(UUID account) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, account);
    }
}