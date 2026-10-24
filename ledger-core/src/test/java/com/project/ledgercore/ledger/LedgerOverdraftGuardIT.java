package com.project.ledgercore.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.common.BusinessRuleException;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class LedgerOverdraftGuardIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    LedgerService ledger;

    @Autowired
    AccountRepository accounts;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aPostingThatWouldOverdrawIsRefusedAndLeavesNothingBehind() {
        Account funding = accounts.create(NewAccount.system("guard-" + UUID.randomUUID(), KZT));
        Account alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        String marker = "overdraft-" + UUID.randomUUID();

        // posted straight to the ledger: no application lock and no balance check, only the database guard
        assertThatThrownBy(() -> ledger.post(new PostingRequest(TransactionType.WITHDRAWAL, marker, List.of(
                PostingLine.debit(alice.id(), Money.of(100, "KZT")),
                PostingLine.credit(funding.id(), Money.of(100, "KZT"))))))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("insufficient-funds");

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transactions WHERE description = ?", Integer.class, marker);
        assertThat(rows).isZero();
        assertThat(ledger.balanceOf(alice.id())).contains(Money.zero(KZT));
    }
}