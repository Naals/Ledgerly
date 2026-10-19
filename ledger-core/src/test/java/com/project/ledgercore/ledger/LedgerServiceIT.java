package com.project.ledgercore.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class LedgerServiceIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    LedgerService ledger;

    @Autowired
    AccountRepository accounts;

    @Autowired
    JdbcTemplate jdbc;

    private Account cashInBank;

    @BeforeEach
    void createFundingAccount() {
        cashInBank = accounts.create(NewAccount.system("cash-in-bank-" + UUID.randomUUID(), KZT));
    }

    @Test
    void depositAndTransferMoveMoneyAndKeepTheBooksBalanced() {
        Account alice = accounts.create(NewAccount.customer("alice", KZT));
        Account bob = accounts.create(NewAccount.customer("bob", KZT));

        ledger.post(new PostingRequest(TransactionType.DEPOSIT, "cash deposit", List.of(
                PostingLine.debit(cashInBank.id(), Money.of(1_000, "KZT")),
                PostingLine.credit(alice.id(), Money.of(1_000, "KZT")))));
        ledger.post(new PostingRequest(TransactionType.TRANSFER, "alice pays bob", List.of(
                PostingLine.debit(alice.id(), Money.of(300, "KZT")),
                PostingLine.credit(bob.id(), Money.of(300, "KZT")))));

        assertThat(ledger.balanceOf(alice.id())).contains(Money.of(700, "KZT"));
        assertThat(ledger.balanceOf(bob.id())).contains(Money.of(300, "KZT"));
        assertThat(ledger.balanceOf(cashInBank.id())).contains(Money.of(-1_000, "KZT"));
    }

    @Test
    void newAccountHasZeroBalance() {
        Account carol = accounts.create(NewAccount.customer("carol", KZT));

        assertThat(ledger.balanceOf(carol.id())).contains(Money.zero(KZT));
    }

    @Test
    void balanceOfUnknownAccountIsEmpty() {
        assertThat(ledger.balanceOf(UUID.randomUUID())).isEmpty();
    }

    @Test
    void aFailureHalfwayThroughRollsBackTheWholePosting() {
        String marker = "atomicity-" + UUID.randomUUID();
        PostingRequest request = new PostingRequest(TransactionType.DEPOSIT, marker, List.of(
                PostingLine.debit(cashInBank.id(), Money.of(500, "KZT")),
                PostingLine.credit(UUID.randomUUID(), Money.of(500, "KZT"))));   // account does not exist

        assertThatThrownBy(() -> ledger.post(request)).isInstanceOf(DataIntegrityViolationException.class);

        Integer transactions = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transactions WHERE description = ?", Integer.class, marker);
        assertThat(transactions).isZero();
        assertThat(ledger.balanceOf(cashInBank.id())).contains(Money.zero(KZT));
    }
}
