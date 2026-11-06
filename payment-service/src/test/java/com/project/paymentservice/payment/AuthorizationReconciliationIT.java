package com.project.paymentservice.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.paymentservice.ledger.HoldInfo;
import com.project.paymentservice.ledger.HoldState;
import com.project.paymentservice.ledger.LedgerOutcome;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthorizationReconciliationIT extends AbstractTwoStepIT {

    private static final int GRACE = 300;

    @BeforeEach
    void clearAuthorizationsEarlierTestsLeftDue() {
        jdbc.update("UPDATE payments SET status = 'CANCELLED' "
                + "WHERE status = 'AUTHORIZED' AND authorization_expires_at <= clock_timestamp()");
    }

    /** An authorization whose hold, as far as this service knows, ended {@code minutesAgo} minutes ago. */
    private Payment authorizedThatEnded(int minutesAgo) {
        ledger.authorizeWith(payment -> new LedgerOutcome.Done<>(new HoldInfo(
                UUID.randomUUID(), HoldState.ACTIVE, OffsetDateTime.now().minusMinutes(minutesAgo), null)));
        Payment payment = payments.create(newKey(), manual()).payment();
        ledger.reset();
        return payment;
    }

    @Test
    void anAuthorizationThatEndedLongAgoBecomesExpiredWhenTheLedgerSaysSo() {
        Payment due = authorizedThatEnded(10);
        ledger.holdIs(holdId -> new LedgerOutcome.Done<>(
                new HoldInfo(holdId, HoldState.EXPIRED, OffsetDateTime.now().minusMinutes(10), null)));

        assertThat(payments.reconcileAuthorizations(GRACE, 50)).isEqualTo(1);

        assertThat(payments.get(due.id()).status()).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test
    void aCaptureNobodyHeardAboutIsFoundByReconciliation() {
        Payment due = authorizedThatEnded(10);
        UUID transaction = UUID.randomUUID();
        ledger.holdIs(holdId -> new LedgerOutcome.Done<>(
                new HoldInfo(holdId, HoldState.CAPTURED, OffsetDateTime.now().minusMinutes(10), transaction)));

        payments.reconcileAuthorizations(GRACE, 50);

        Payment payment = payments.get(due.id());
        assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.ledgerTransactionId()).isEqualTo(transaction);
    }

    @Test
    void anAuthorizationThatEndedAMomentAgoIsLeftToTheHoldEvents() {
        Payment recent = authorizedThatEnded(1);

        assertThat(payments.reconcileAuthorizations(GRACE, 50)).isZero();

        assertThat(ledger.holdCalls()).isEmpty();
        assertThat(payments.get(recent.id()).status()).isEqualTo(PaymentStatus.AUTHORIZED);
        jdbc.update("UPDATE payments SET status = 'CANCELLED' WHERE id = ?", recent.id());   // leave nothing due behind
    }

    @Test
    void aHoldTheLedgerHasNotEndedYetIsLeftAloneForTheNextRun() {
        Payment due = authorizedThatEnded(10);

        assertThat(payments.reconcileAuthorizations(GRACE, 50)).isZero();

        assertThat(payments.get(due.id()).status()).isEqualTo(PaymentStatus.AUTHORIZED);
        jdbc.update("UPDATE payments SET status = 'CANCELLED' WHERE id = ?", due.id());
    }

    @Test
    void aDownLedgerStopsTheRunAfterTheFirstPaymentInsteadOfTimingOutOnEveryOne() {
        Payment first = authorizedThatEnded(10);
        Payment second = authorizedThatEnded(11);
        Payment third = authorizedThatEnded(12);
        ledger.holdIs(holdId -> new LedgerOutcome.Unavailable<>("down"));

        assertThat(payments.reconcileAuthorizations(GRACE, 50)).isZero();

        assertThat(ledger.holdCalls()).containsExactly("findHold");
        for (Payment payment : new Payment[] {first, second, third}) {
            assertThat(payments.get(payment.id()).status()).isEqualTo(PaymentStatus.AUTHORIZED);
            jdbc.update("UPDATE payments SET status = 'CANCELLED' WHERE id = ?", payment.id());
        }
    }

    @Test
    void anAuthorizationThatHasNotEndedIsNotEvenAsked() {
        authorized();

        assertThat(payments.reconcileAuthorizations(GRACE, 50)).isZero();

        assertThat(ledger.holdCalls()).isEmpty();
    }
}