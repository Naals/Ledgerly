package com.project.paymentservice.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.paymentservice.events.EventMetrics;
import com.project.paymentservice.ledger.HoldInfo;
import com.project.paymentservice.ledger.HoldState;
import com.project.paymentservice.ledger.LedgerOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AuthorizationReconciliationJobIT extends AbstractTwoStepIT {

    @Autowired
    EventMetrics metrics;

    @Autowired
    MeterRegistry registry;

    @BeforeEach
    void clearAuthorizationsEarlierTestsLeftDue() {
        jdbc.update("UPDATE payments SET status = 'CANCELLED' "
                + "WHERE status = 'AUTHORIZED' AND authorization_expires_at <= clock_timestamp()");
    }

    @Test
    void whatTheEventsAlreadySettledIsNotFoundAndNothingIsCounted() {
        authorizedThatEnded(1);
        double before = settledCount();

        new AuthorizationReconciliationJob(payments, metrics, 50, 300).reconcileAuthorizations();

        assertThat(settledCount()).isEqualTo(before);
        jdbc.update("UPDATE payments SET status = 'CANCELLED' WHERE status = 'AUTHORIZED' AND authorization_expires_at <= clock_timestamp()");
    }

    @Test
    void whatTheEventsMissedIsSettledAndCounted() {
        Payment missed = authorizedThatEnded(10);
        Payment alsoMissed = authorizedThatEnded(20);
        ledger.holdIs(holdId -> new LedgerOutcome.Done<>(
                new HoldInfo(holdId, HoldState.EXPIRED, OffsetDateTime.now().minusMinutes(10), null)));
        double before = settledCount();

        new AuthorizationReconciliationJob(payments, metrics, 50, 300).reconcileAuthorizations();

        assertThat(settledCount()).isEqualTo(before + 2);
        assertThat(payments.get(missed.id()).status()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(payments.get(alsoMissed.id()).status()).isEqualTo(PaymentStatus.EXPIRED);
    }

    private Payment authorizedThatEnded(int minutesAgo) {
        ledger.authorizeWith(payment -> new LedgerOutcome.Done<>(new HoldInfo(
                UUID.randomUUID(), HoldState.ACTIVE, OffsetDateTime.now().minusMinutes(minutesAgo), null)));
        Payment payment = payments.create(newKey(), manual()).payment();
        ledger.reset();
        return payment;
    }

    private double settledCount() {
        return registry.counter("payments.reconciliation.settled").count();
    }
}