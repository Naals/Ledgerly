package com.project.ledgercore.hold;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.LedgerService;
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

class HoldExpiryIT extends AbstractIntegrationTest {

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
        cash.deposit(alice.id(), Money.of(10_000, "KZT"));
        cash.deposit(bob.id(), Money.of(10_000, "KZT"));
    }

    @Test
    void aHoldThatRanOutExpiresAndFreesItsFunds() {
        UUID due = fixtures.insertHold(alice.id(), bob.id(), 400, "KZT", -5);

        assertThat(holds.expireDue(100)).isGreaterThanOrEqualTo(1);

        assertThat(holds.get(due).status()).isEqualTo(HoldStatus.EXPIRED);
        assertThat(ledger.balanceSummaryOf(alice.id()).orElseThrow().available()).isEqualTo(Money.of(10_000, "KZT"));
        invariants.assertHeldMatchesActiveHolds();
    }

    @Test
    void aHoldThatHasNotRunOutIsLeftAlone() {
        UUID early = fixtures.insertHold(alice.id(), bob.id(), 400, "KZT", 3_600);

        holds.expireDue(100);

        assertThat(holds.get(early).status()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(ledger.balanceSummaryOf(alice.id()).orElseThrow().held()).isEqualTo(Money.of(400, "KZT"));
    }

    @Test
    void theJobKeepsSweepingUntilNothingIsDue() {
        List<UUID> due = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            due.add(fixtures.insertHold(alice.id(), bob.id(), 100, "KZT", -5));
        }

        new HoldExpiryJob(holds, 2).expireDueHolds();

        for (UUID hold : due) {
            assertThat(holds.get(hold).status()).isEqualTo(HoldStatus.EXPIRED);
        }
        invariants.assertHeldMatchesActiveHolds();
    }

    @Test
    void sweepsRunningAtTheSameTimeNeitherDeadlockNorExpireTwice() throws Exception {
        List<UUID> due = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            due.add(fixtures.insertHold(alice.id(), bob.id(), 10, "KZT", -5));
            due.add(fixtures.insertHold(bob.id(), alice.id(), 10, "KZT", -5));
        }
        List<Callable<Integer>> sweeps = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            sweeps.add(() -> holds.expireDue(10));
        }

        ConcurrentRunner.runAll(4, sweeps);
        holds.expireDue(1_000);   // whatever the four batches left behind

        for (UUID hold : due) {
            assertThat(holds.get(hold).status()).isEqualTo(HoldStatus.EXPIRED);
        }
        assertThat(ledger.balanceSummaryOf(alice.id()).orElseThrow().held()).isEqualTo(Money.of(0, "KZT"));
        assertThat(ledger.balanceSummaryOf(bob.id()).orElseThrow().held()).isEqualTo(Money.of(0, "KZT"));
        invariants.assertHeldMatchesActiveHolds();
    }
}