package com.project.ledgercore.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountNotActiveException;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.CurrencyMismatchException;
import com.project.ledgercore.account.InsufficientFundsException;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.BusinessRuleException;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.BalanceSummary;
import com.project.ledgercore.ledger.LedgerService;
import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.support.LedgerInvariants;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class HoldServiceIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");
    private static final Currency USD = Currency.getInstance("USD");

    @Autowired
    HoldService holds;

    @Autowired
    AccountRepository accounts;

    @Autowired
    CashService cash;

    @Autowired
    LedgerService ledger;

    @Autowired
    LedgerInvariants invariants;

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
    void aHoldReservesTheAmount() {
        Hold hold = holds.place(alice.id(), bob.id(), Money.of(300, "KZT"), "payment-42", null);

        assertThat(hold.status()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(hold.reference()).isEqualTo("payment-42");
        assertThat(hold.expired()).isFalse();
        BalanceSummary summary = ledger.balanceSummaryOf(alice.id()).orElseThrow();
        assertThat(summary.held()).isEqualTo(Money.of(300, "KZT"));
        assertThat(summary.available()).isEqualTo(Money.of(700, "KZT"));
        invariants.assertHeldMatchesActiveHolds();
    }

    @Test
    void holdsCannotReserveMoreThanIsAvailable() {
        assertThatThrownBy(() -> holds.place(alice.id(), bob.id(), Money.of(1_001, "KZT"), null, null))
                .isInstanceOf(InsufficientFundsException.class);

        holds.place(alice.id(), bob.id(), Money.of(700, "KZT"), null, null);

        assertThatThrownBy(() -> holds.place(alice.id(), bob.id(), Money.of(301, "KZT"), null, null))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void aFrozenAccountCannotPlaceHolds() {
        jdbc.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?", alice.id());

        assertThatThrownBy(() -> holds.place(alice.id(), bob.id(), Money.of(100, "KZT"), null, null))
                .isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    void thePayeeMustBeACustomerAccountInTheSameCurrency() {
        Account dollars = accounts.create(NewAccount.customer("dollars-" + UUID.randomUUID(), USD));
        Account bank = accounts.create(NewAccount.system("bank-" + UUID.randomUUID(), KZT));

        assertThatThrownBy(() -> holds.place(alice.id(), dollars.id(), Money.of(100, "KZT"), null, null))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> holds.place(alice.id(), bank.id(), Money.of(100, "KZT"), null, null))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("not-a-customer-account");
    }

    @Test
    void releasingAHoldFreesTheFunds() {
        Hold hold = holds.place(alice.id(), bob.id(), Money.of(1_000, "KZT"), null, null);

        Hold released = holds.release(hold.id());

        assertThat(released.status()).isEqualTo(HoldStatus.RELEASED);
        assertThat(ledger.balanceSummaryOf(alice.id()).orElseThrow().available()).isEqualTo(Money.of(1_000, "KZT"));
        invariants.assertHeldMatchesActiveHolds();
    }

    @Test
    void releasingTwiceIsHarmless() {
        Hold hold = holds.place(alice.id(), bob.id(), Money.of(300, "KZT"), null, null);
        holds.release(hold.id());

        assertThat(holds.release(hold.id()).status()).isEqualTo(HoldStatus.RELEASED);
        assertThat(ledger.balanceSummaryOf(alice.id()).orElseThrow().held()).isEqualTo(Money.of(0, "KZT"));
    }

    @Test
    void anUnknownHoldIsNotFound() {
        assertThatThrownBy(() -> holds.release(UUID.randomUUID())).isInstanceOf(HoldNotFoundException.class);
        assertThatThrownBy(() -> holds.get(UUID.randomUUID())).isInstanceOf(HoldNotFoundException.class);
    }
}