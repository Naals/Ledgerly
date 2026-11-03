package com.project.ledgercore.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.InsufficientFundsException;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.BalanceSummary;
import com.project.ledgercore.ledger.LedgerService;
import com.project.ledgercore.ledger.TransactionType;
import com.project.ledgercore.ledger.TransactionView;
import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.support.ConcurrentRunner;
import com.project.ledgercore.support.LedgerFixtures;
import com.project.ledgercore.support.LedgerInvariants;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class HoldCaptureIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    HoldService holds;

    @Autowired
    AccountRepository accounts;

    @Autowired
    CashService cash;

    @Autowired
    LedgerService ledger;

    @Autowired
    LedgerFixtures fixtures;

    @Autowired
    LedgerInvariants invariants;

    private Account alice;
    private Account bob;

    @BeforeEach
    void openAccounts() {
        alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        bob = accounts.create(NewAccount.customer("bob-" + UUID.randomUUID(), KZT));
        cash.deposit(alice.id(), Money.of(1_000, "KZT"));
    }

    @Test
    void capturingMovesTheMoneyAndFreesTheHold() {
        Hold hold = holds.place(alice.id(), bob.id(), Money.of(300, "KZT"), "payment-42", null);

        Capture capture = holds.capture(hold.id(), null);

        assertThat(capture.captured()).isEqualTo(Money.of(300, "KZT"));
        BalanceSummary payer = ledger.balanceSummaryOf(alice.id()).orElseThrow();
        assertThat(payer.balance()).isEqualTo(Money.of(700, "KZT"));
        assertThat(payer.held()).isEqualTo(Money.of(0, "KZT"));
        assertThat(ledger.balanceOf(bob.id()).orElseThrow()).isEqualTo(Money.of(300, "KZT"));

        Hold captured = holds.get(hold.id());
        assertThat(captured.status()).isEqualTo(HoldStatus.CAPTURED);
        assertThat(captured.captured()).isEqualTo(Money.of(300, "KZT"));
        assertThat(captured.ledgerTransactionId()).isEqualTo(capture.transactionId());

        TransactionView transaction = ledger.transaction(capture.transactionId());
        assertThat(transaction.type()).isEqualTo(TransactionType.TRANSFER);
        assertThat(transaction.reference()).isEqualTo("payment-42");
        invariants.assertHeldMatchesActiveHolds();
        invariants.assertBalanceCacheMatchesEntries();
    }

    @Test
    void aPartialCaptureFreesTheRestOfTheHold() {
        Hold hold = holds.place(alice.id(), bob.id(), Money.of(300, "KZT"), null, null);

        Capture capture = holds.capture(hold.id(), 120L);

        assertThat(capture.captured()).isEqualTo(Money.of(120, "KZT"));
        BalanceSummary payer = ledger.balanceSummaryOf(alice.id()).orElseThrow();
        assertThat(payer.balance()).isEqualTo(Money.of(880, "KZT"));
        assertThat(payer.available()).isEqualTo(Money.of(880, "KZT"));
        assertThat(ledger.balanceOf(bob.id()).orElseThrow()).isEqualTo(Money.of(120, "KZT"));
    }

    @Test
    void aCaptureCannotTakeMoreThanTheHold() {
        Hold hold = holds.place(alice.id(), bob.id(), Money.of(300, "KZT"), null, null);

        assertThatThrownBy(() -> holds.capture(hold.id(), 301L)).isInstanceOf(CaptureExceedsHoldException.class);

        assertThat(holds.get(hold.id()).status()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(ledger.balanceSummaryOf(alice.id()).orElseThrow().held()).isEqualTo(Money.of(300, "KZT"));
    }

    @Test
    void aHoldIsCapturedOnlyOnce() {
        Hold hold = holds.place(alice.id(), bob.id(), Money.of(300, "KZT"), null, null);
        holds.capture(hold.id(), null);

        assertThatThrownBy(() -> holds.capture(hold.id(), null)).isInstanceOf(HoldNotActiveException.class);

        assertThat(ledger.balanceOf(bob.id()).orElseThrow()).isEqualTo(Money.of(300, "KZT"));
    }

    @Test
    void aReleasedHoldCannotBeCaptured() {
        Hold hold = holds.place(alice.id(), bob.id(), Money.of(300, "KZT"), null, null);
        holds.release(hold.id());

        assertThatThrownBy(() -> holds.capture(hold.id(), null)).isInstanceOf(HoldNotActiveException.class);
    }

    @Test
    void anExpiredHoldCannotBeCapturedEvenBeforeTheSweepNoticesIt() {
        UUID hold = fixtures.insertHold(alice.id(), bob.id(), 300, "KZT", -5);

        assertThatThrownBy(() -> holds.capture(hold, null)).isInstanceOf(HoldExpiredException.class);

        assertThat(ledger.balanceOf(bob.id()).orElseThrow()).isEqualTo(Money.of(0, "KZT"));
    }

    @Test
    void concurrentCapturesOfTheSameHoldMoveMoneyOnce() throws Exception {
        UUID hold = holds.place(alice.id(), bob.id(), Money.of(300, "KZT"), null, null).id();
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> {
                try {
                    holds.capture(hold, null);
                    return true;
                } catch (HoldNotActiveException e) {
                    return false;
                }
            });
        }

        List<Boolean> results = ConcurrentRunner.runAll(8, tasks);

        assertThat(results.stream().filter(captured -> captured).count()).isEqualTo(1);
        assertThat(ledger.balanceOf(bob.id()).orElseThrow()).isEqualTo(Money.of(300, "KZT"));
        invariants.assertHeldMatchesActiveHolds();
    }

    @Test
    void withdrawalsCannotStarveACapture() throws Exception {
        UUID hold = holds.place(alice.id(), bob.id(), Money.of(600, "KZT"), null, null).id();
        List<Callable<String>> tasks = new ArrayList<>();
        tasks.add(() -> {
            holds.capture(hold, null);
            return "captured";
        });
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> {
                try {
                    cash.withdraw(alice.id(), Money.of(100, "KZT"));
                    return "withdrawn";
                } catch (InsufficientFundsException e) {
                    return "refused";
                }
            });
        }

        List<String> results = ConcurrentRunner.runAll(11, tasks);

        // 1,000 = 600 reserved for the capture + 4 x 100 that were free
        assertThat(results).containsOnlyOnce("captured");
        assertThat(results.stream().filter("withdrawn"::equals).count()).isEqualTo(4);
        assertThat(ledger.balanceOf(alice.id()).orElseThrow()).isEqualTo(Money.of(0, "KZT"));
        assertThat(ledger.balanceOf(bob.id()).orElseThrow()).isEqualTo(Money.of(600, "KZT"));
        invariants.assertHeldMatchesActiveHolds();
    }
}