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

    @BeforeEach
    void clearAuthorizationsEarlierTestsLeftDue() {
        jdbc.update("UPDATE payments SET status = 'CANCELLED' "
                + "WHERE status = 'AUTHORIZED' AND authorization_expires_at <= clock_timestamp()");
    }

    /** An authorization whose hold, as far as this service knows, ran out a minute ago. */
    private Payment authorizedAndDue() {
        ledger.authorizeWith(payment -> new LedgerOutcome.Done<>(
                new HoldInfo(UUID.randomUUID(), HoldState.ACTIVE, OffsetDateTime.now().minusMinutes(1), null)));
        Payment payment = payments.create(newKey(), manual()).payment();
        ledger.reset();
        return payment;
    }

    @Test
    void anAuthorizationThatRanOutBecomesExpiredWhenTheLedgerSaysSo() {
        Payment due = authorizedAndDue();
        ledger.holdIs(holdId -> new LedgerOutcome.Done<>(
                new HoldInfo(holdId, HoldState.EXPIRED, OffsetDateTime.now().minusMinutes(1), null)));

        assertThat(payments.reconcileAuthorizations(50)).isEqualTo(1);

        assertThat(payments.get(due.id()).status()).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test
    void aCaptureTheServiceNeverHeardAboutIsFoundByReconciliation() {
        Payment due = authorizedAndDue();
        UUID transaction = UUID.randomUUID();
        ledger.holdIs(holdId -> new LedgerOutcome.Done<>(
                new HoldInfo(holdId, HoldState.CAPTURED, OffsetDateTime.now().minusMinutes(1), transaction)));

        payments.reconcileAuthorizations(50);

        Payment payment = payments.get(due.id());
        assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.ledgerTransactionId()).isEqualTo(transaction);
    }

    @Test
    void aHoldTheLedgerHasNotExpiredYetIsLeftAloneForTheNextRun() {
        Payment due = authorizedAndDue();

        assertThat(payments.reconcileAuthorizations(50)).isZero();

        assertThat(payments.get(due.id()).status()).isEqualTo(PaymentStatus.AUTHORIZED);
        jdbc.update("UPDATE payments SET status = 'CANCELLED' WHERE id = ?", due.id());   // leave nothing due behind
    }

    @Test
    void aDownLedgerStopsTheRunAfterTheFirstPaymentInsteadOfTimingOutOnEveryOne() {
        Payment first = authorizedAndDue();
        Payment second = authorizedAndDue();
        Payment third = authorizedAndDue();
        ledger.holdIs(holdId -> new LedgerOutcome.Unavailable<>("down"));

        assertThat(payments.reconcileAuthorizations(50)).isZero();

        assertThat(ledger.holdCalls()).containsExactly("findHold");
        for (Payment payment : new Payment[] {first, second, third}) {
            assertThat(payments.get(payment.id()).status()).isEqualTo(PaymentStatus.AUTHORIZED);
            jdbc.update("UPDATE payments SET status = 'CANCELLED' WHERE id = ?", payment.id());
        }
    }

    @Test
    void anAuthorizationThatHasNotRunOutIsNotEvenAsked() {
        authorized();

        assertThat(payments.reconcileAuthorizations(50)).isZero();

        assertThat(ledger.holdCalls()).isEmpty();
    }
}