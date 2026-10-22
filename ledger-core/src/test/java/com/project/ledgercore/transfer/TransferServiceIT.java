package com.project.ledgercore.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountNotActiveException;
import com.project.ledgercore.account.AccountNotFoundException;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.CurrencyMismatchException;
import com.project.ledgercore.account.InsufficientFundsException;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.account.SystemAccounts;
import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.BadRequestException;
import com.project.ledgercore.common.BusinessRuleException;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.LedgerService;
import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class TransferServiceIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");
    private static final Currency USD = Currency.getInstance("USD");

    @Autowired
    TransferService transfers;

    @Autowired
    AccountRepository accounts;

    @Autowired
    SystemAccounts systemAccounts;

    @Autowired
    CashService cash;

    @Autowired
    LedgerService ledger;

    @Autowired
    JdbcTemplate jdbc;

    private Account alice;
    private Account bob;

    @BeforeEach
    void openAccounts() {
        alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        bob = accounts.create(NewAccount.customer("bob-" + UUID.randomUUID(), KZT));
        cash.deposit(alice.id(), Money.of(1_000, "KZT"));
    }

    @Test
    void transferMovesMoneyBetweenCustomerAccounts() {
        UUID transactionId = transfers.transfer(alice.id(), bob.id(), Money.of(400, "KZT"));

        assertThat(transactionId).isNotNull();
        assertThat(ledger.balanceOf(alice.id())).contains(Money.of(600, "KZT"));
        assertThat(ledger.balanceOf(bob.id())).contains(Money.of(400, "KZT"));
    }

    @Test
    void transferBeyondTheBalanceIsRejectedAndChangesNothing() {
        assertThatThrownBy(() -> transfers.transfer(alice.id(), bob.id(), Money.of(1_001, "KZT")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(ledger.balanceOf(alice.id())).contains(Money.of(1_000, "KZT"));
        assertThat(ledger.balanceOf(bob.id())).contains(Money.zero(KZT));
    }

    @Test
    void cannotTransferToTheSameAccount() {
        assertThatThrownBy(() -> transfers.transfer(alice.id(), alice.id(), Money.of(100, "KZT")))
                .isInstanceOf(BadRequestException.class)
                .extracting(e -> ((BadRequestException) e).code()).isEqualTo("same-account");
    }

    @Test
    void unknownAccountsAreRejected() {
        assertThatThrownBy(() -> transfers.transfer(UUID.randomUUID(), bob.id(), Money.of(100, "KZT")))
                .isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> transfers.transfer(alice.id(), UUID.randomUUID(), Money.of(100, "KZT")))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void currencyMustMatchBothAccounts() {
        Account bobUsd = accounts.create(NewAccount.customer("bob-usd-" + UUID.randomUUID(), USD));

        assertThatThrownBy(() -> transfers.transfer(alice.id(), bobUsd.id(), Money.of(100, "KZT")))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> transfers.transfer(alice.id(), bob.id(), Money.of(100, "USD")))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void frozenAccountsCannotTakePart() {
        jdbc.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?", bob.id());
        assertThatThrownBy(() -> transfers.transfer(alice.id(), bob.id(), Money.of(100, "KZT")))
                .isInstanceOf(AccountNotActiveException.class);

        jdbc.update("UPDATE accounts SET status = 'ACTIVE' WHERE id = ?", bob.id());
        jdbc.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?", alice.id());
        assertThatThrownBy(() -> transfers.transfer(alice.id(), bob.id(), Money.of(100, "KZT")))
                .isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    void nonPositiveAmountIsRejected() {
        assertThatThrownBy(() -> transfers.transfer(alice.id(), bob.id(), Money.zero(KZT)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> transfers.transfer(alice.id(), bob.id(), Money.of(-5, "KZT")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void theBanksOwnAccountsCannotTakePartInCustomerTransfers() {
        Account cashInBank = systemAccounts.cashInBank(KZT);

        assertThatThrownBy(() -> transfers.transfer(cashInBank.id(), bob.id(), Money.of(100, "KZT")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not a customer account");
        assertThatThrownBy(() -> transfers.transfer(alice.id(), cashInBank.id(), Money.of(100, "KZT")))
                .isInstanceOf(BusinessRuleException.class);
    }
}
