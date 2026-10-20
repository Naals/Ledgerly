package com.project.ledgercore.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountNotActiveException;
import com.project.ledgercore.account.AccountNotFoundException;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.CurrencyMismatchException;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.account.SystemAccounts;
import com.project.ledgercore.common.BadRequestException;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.LedgerService;
import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CashServiceIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    CashService cash;

    @Autowired
    AccountRepository accounts;

    @Autowired
    SystemAccounts systemAccounts;

    @Autowired
    LedgerService ledger;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void depositCreditsTheCustomerAndDebitsCashInBank() {
        Account alice = accounts.create(NewAccount.customer("alice", KZT));
        Account cashInBank = systemAccounts.cashInBank(KZT);
        Money cashBefore = ledger.balanceOf(cashInBank.id()).orElseThrow();

        UUID transactionId = cash.deposit(alice.id(), Money.of(1_000, "KZT"));

        assertThat(transactionId).isNotNull();
        assertThat(ledger.balanceOf(alice.id())).contains(Money.of(1_000, "KZT"));
        assertThat(ledger.balanceOf(cashInBank.id())).contains(cashBefore.minus(Money.of(1_000, "KZT")));
    }

    @Test
    void depositToUnknownAccountFails() {
        assertThatThrownBy(() -> cash.deposit(UUID.randomUUID(), Money.of(100, "KZT")))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void depositToFrozenAccountFails() {
        Account frozen = accounts.create(NewAccount.customer("frozen", KZT));
        jdbc.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?", frozen.id());

        assertThatThrownBy(() -> cash.deposit(frozen.id(), Money.of(100, "KZT")))
                .isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    void depositInAnotherCurrencyFails() {
        Account alice = accounts.create(NewAccount.customer("alice", KZT));

        assertThatThrownBy(() -> cash.deposit(alice.id(), Money.of(100, "USD")))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void nonPositiveAmountIsRejected() {
        Account alice = accounts.create(NewAccount.customer("alice", KZT));

        assertThatThrownBy(() -> cash.deposit(alice.id(), Money.zero(KZT)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> cash.deposit(alice.id(), Money.of(-5, "KZT")))
                .isInstanceOf(BadRequestException.class);
    }
}