package com.project.ledgercore.hold;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.CorrelationContext;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.outbox.OutboxEvent;
import com.project.ledgercore.outbox.OutboxRepository;
import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.support.LedgerFixtures;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class HoldCorrelationIT extends AbstractIntegrationTest {

    @Autowired HoldService holds;
    @Autowired AccountRepository accounts;
    @Autowired CashService cash;
    @Autowired OutboxRepository outbox;
    @Autowired LedgerFixtures fixtures;
    @Autowired TransactionTemplate tx;

    private Account payer;
    private Account payee;

    @BeforeEach
    void fundAccounts() {
        Currency currency = Currency.getInstance("KZT");
        payer = accounts.create(NewAccount.customer("correlation-payer-" + UUID.randomUUID(), currency));
        payee = accounts.create(NewAccount.customer("correlation-payee-" + UUID.randomUUID(), currency));
        cash.deposit(payer.id(), Money.of(1_000, "KZT"));
    }

    @Test
    void captureCorrelatesItsPostingAndItsDeferredHoldEvent() {
        Hold hold = newHold();
        Capture capture;
        try (CorrelationContext ignored = CorrelationContext.open("capture-request")) {
            capture = holds.capture(hold.id(), null);
        }

        assertThat(event(hold.id()).correlationId()).isEqualTo("capture-request");
        assertThat(event(capture.transactionId()).correlationId()).isEqualTo("capture-request");
    }

    @Test
    void deferredEventsKeepTheContextOfEachStatusChangeRatherThanTheLastTransactionContext() {
        Hold first = newHold();
        Hold second = newHold();
        Hold uncorrelated = newHold();
        tx.executeWithoutResult(status -> {
            try (CorrelationContext ignored = CorrelationContext.open("first-request")) {
                holds.release(first.id());
            }
            try (CorrelationContext ignored = CorrelationContext.open("second-request")) {
                holds.release(second.id());
            }
            holds.release(uncorrelated.id());
            // Deferred hold events are written only after all three contexts have ended.
            assertThat(outbox.findByAggregate(first.id())).isEmpty();
        });

        assertThat(event(first.id()).correlationId()).isEqualTo("first-request");
        assertThat(event(second.id()).correlationId()).isEqualTo("second-request");
        assertThat(event(uncorrelated.id()).correlationId()).isNull();
    }

    @Test
    void anExpirySweepCanCarryItsOwnCorrelationId() {
        UUID holdId = fixtures.insertHold(payer.id(), payee.id(), 100, "KZT", -5);
        try (CorrelationContext ignored = CorrelationContext.open("expiry-run")) {
            holds.expireDue(100);
        }
        assertThat(event(holdId).correlationId()).isEqualTo("expiry-run");
    }

    @Test
    void aRolledBackStatusChangeCannotLeaveAnEventOrItsCorrelation() {
        Hold hold = newHold();
        tx.executeWithoutResult(status -> {
            try (CorrelationContext ignored = CorrelationContext.open("rolled-back")) {
                holds.release(hold.id());
            }
            status.setRollbackOnly();
        });
        assertThat(outbox.findByAggregate(hold.id())).isEmpty();
        assertThat(holds.get(hold.id()).status()).isEqualTo(HoldStatus.ACTIVE);

        holds.release(hold.id());
        assertThat(event(hold.id()).correlationId()).isNull();
    }

    private Hold newHold() {
        return holds.place(payer.id(), payee.id(), Money.of(100, "KZT"), null, null);
    }

    private OutboxEvent event(UUID aggregateId) {
        return outbox.findByAggregate(aggregateId).getFirst();
    }
}
