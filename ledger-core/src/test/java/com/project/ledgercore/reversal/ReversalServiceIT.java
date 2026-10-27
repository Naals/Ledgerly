package com.project.ledgercore.reversal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountNotActiveException;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.InsufficientFundsException;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.LedgerService;
import com.project.ledgercore.ledger.TransactionType;
import com.project.ledgercore.ledger.TransactionView;
import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.support.ConcurrentRunner;
import com.project.ledgercore.transfer.TransferService;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ReversalServiceIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    ReversalService reversals;

    @Autowired
    CashService cash;

    @Autowired
    TransferService transfers;

    @Autowired
    AccountRepository accounts;

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
    }

    @Test
    void reversingADepositTakesTheMoneyBack() {
        UUID deposit = cash.deposit(alice.id(), Money.of(500, "KZT"));

        UUID reversal = reversals.reverse(deposit, "entered twice");

        assertThat(ledger.balanceOf(alice.id())).contains(Money.zero(KZT));
        TransactionView view = ledger.transaction(reversal);
        assertThat(view.type()).isEqualTo(TransactionType.REVERSAL);
        assertThat(view.description()).contains("entered twice");
    }

    @Test
    void reversingAWithdrawalGivesTheMoneyBack() {
        cash.deposit(alice.id(), Money.of(500, "KZT"));
        UUID withdrawal = cash.withdraw(alice.id(), Money.of(200, "KZT"));

        reversals.reverse(withdrawal, null);

        assertThat(ledger.balanceOf(alice.id())).contains(Money.of(500, "KZT"));
    }

    @Test
    void reversingATransferRestoresBothAccounts() {
        cash.deposit(alice.id(), Money.of(1_000, "KZT"));
        UUID transfer = transfers.transfer(alice.id(), bob.id(), Money.of(400, "KZT"));

        reversals.reverse(transfer, "wrong account");

        assertThat(ledger.balanceOf(alice.id())).contains(Money.of(1_000, "KZT"));
        assertThat(ledger.balanceOf(bob.id())).contains(Money.zero(KZT));
    }

    @Test
    void aTransactionCanBeReversedOnlyOnce() {
        cash.deposit(alice.id(), Money.of(1_000, "KZT"));
        UUID transfer = transfers.transfer(alice.id(), bob.id(), Money.of(400, "KZT"));
        reversals.reverse(transfer, null);

        assertThatThrownBy(() -> reversals.reverse(transfer, null))
                .isInstanceOf(AlreadyReversedException.class);
    }

    @Test
    void aReversalCannotBeReversed() {
        UUID deposit = cash.deposit(alice.id(), Money.of(500, "KZT"));
        UUID reversal = reversals.reverse(deposit, null);

        assertThatThrownBy(() -> reversals.reverse(reversal, null))
                .isInstanceOf(NotReversibleException.class);
    }

    @Test
    void aReversalNeedsTheMoneyToStillBeThere() {
        cash.deposit(alice.id(), Money.of(1_000, "KZT"));
        UUID transfer = transfers.transfer(alice.id(), bob.id(), Money.of(400, "KZT"));
        cash.withdraw(bob.id(), Money.of(300, "KZT"));   // bob already spent most of it

        assertThatThrownBy(() -> reversals.reverse(transfer, null))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(ledger.balanceOf(alice.id())).contains(Money.of(600, "KZT"));
        assertThat(ledger.balanceOf(bob.id())).contains(Money.of(100, "KZT"));
        assertThat(ledger.reversalOf(transfer)).isEmpty();
    }

    @Test
    void aFrozenAccountBlocksTheReversal() {
        cash.deposit(alice.id(), Money.of(1_000, "KZT"));
        UUID transfer = transfers.transfer(alice.id(), bob.id(), Money.of(400, "KZT"));
        jdbc.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?", bob.id());

        assertThatThrownBy(() -> reversals.reverse(transfer, null))
                .isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    void concurrentReversalsOfOneTransactionSucceedExactlyOnce() throws Exception {
        cash.deposit(alice.id(), Money.of(1_000, "KZT"));
        UUID transfer = transfers.transfer(alice.id(), bob.id(), Money.of(400, "KZT"));

        List<Callable<Boolean>> attempts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            attempts.add(() -> {
                try {
                    reversals.reverse(transfer, "race");
                    return true;
                } catch (AlreadyReversedException e) {
                    return false;
                }
            });
        }

        long succeeded = ConcurrentRunner.runAll(8, attempts).stream().filter(Boolean::booleanValue).count();

        assertThat(succeeded).isEqualTo(1);
        assertThat(ledger.balanceOf(alice.id())).contains(Money.of(1_000, "KZT"));
        assertThat(ledger.balanceOf(bob.id())).contains(Money.zero(KZT));
    }
}