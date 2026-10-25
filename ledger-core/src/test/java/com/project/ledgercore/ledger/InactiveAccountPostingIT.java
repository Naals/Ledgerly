package com.project.ledgercore.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.common.ConflictException;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class InactiveAccountPostingIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    LedgerService ledger;

    @Autowired
    AccountRepository accounts;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aPostingToAFrozenAccountIsAConflictAndLeavesNothingBehind() {
        assertRefusedFor("FROZEN");
    }

    @Test
    void aPostingToAClosedAccountIsAConflictToo() {
        assertRefusedFor("CLOSED");
    }

    private void assertRefusedFor(String status) {
        Account funding = accounts.create(NewAccount.system("inactive-" + UUID.randomUUID(), KZT));
        Account alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        jdbc.update("UPDATE accounts SET status = ? WHERE id = ?", status, alice.id());
        String marker = "inactive-" + UUID.randomUUID();

        assertThatThrownBy(() -> ledger.post(new PostingRequest(TransactionType.DEPOSIT, marker, List.of(
                PostingLine.debit(funding.id(), Money.of(100, "KZT")),
                PostingLine.credit(alice.id(), Money.of(100, "KZT"))))))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).code()).isEqualTo("account-not-active");

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transactions WHERE description = ?", Integer.class, marker);
        assertThat(rows).isZero();
    }
}