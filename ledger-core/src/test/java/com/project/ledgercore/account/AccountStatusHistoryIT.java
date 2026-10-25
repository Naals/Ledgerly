package com.project.ledgercore.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class AccountStatusHistoryIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    AccountLifecycleService lifecycle;

    @Autowired
    AccountRepository accounts;

    @Autowired
    CashService cash;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void everyChangeIsRecordedWithItsReasonNewestFirst() {
        Account alice = newAccount();

        lifecycle.freeze(alice.id(), "fraud check");
        lifecycle.unfreeze(alice.id(), "  cleared  ");
        lifecycle.close(alice.id());

        assertThat(lifecycle.history(alice.id()))
                .extracting(AccountStatusEvent::from, AccountStatusEvent::to, AccountStatusEvent::reason)
                .containsExactly(
                        tuple(AccountStatus.ACTIVE, AccountStatus.CLOSED, null),
                        tuple(AccountStatus.FROZEN, AccountStatus.ACTIVE, "cleared"),
                        tuple(AccountStatus.ACTIVE, AccountStatus.FROZEN, "fraud check"));
    }

    @Test
    void aRepeatedRequestRecordsNothing() {
        Account alice = newAccount();

        lifecycle.freeze(alice.id(), "first");
        lifecycle.freeze(alice.id(), "again");

        assertThat(lifecycle.history(alice.id())).hasSize(1);
    }

    @Test
    void aRefusedChangeRecordsNothing() {
        Account alice = newAccount();
        cash.deposit(alice.id(), Money.of(100, "KZT"));

        assertThatThrownBy(() -> lifecycle.close(alice.id(), "customer request"));

        assertThat(lifecycle.history(alice.id())).isEmpty();
    }

    @Test
    void historyCannotBeEdited() {
        Account alice = newAccount();
        lifecycle.freeze(alice.id(), "test");

        assertThatThrownBy(() -> jdbc.update("UPDATE account_status_events SET reason = 'x' WHERE account_id = ?",
                alice.id())).hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM account_status_events WHERE account_id = ?",
                alice.id())).hasStackTraceContaining("append-only");
    }

    private Account newAccount() {
        return accounts.create(NewAccount.customer("history-" + UUID.randomUUID(), KZT));
    }
}