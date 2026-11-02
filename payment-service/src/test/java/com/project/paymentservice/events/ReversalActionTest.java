package com.project.paymentservice.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.paymentservice.payment.PaymentStatus;
import org.junit.jupiter.api.Test;

class ReversalActionTest {

    @Test
    void aCompletedPaymentIsReversed() {
        assertThat(ReversalAction.forPayment(PaymentStatus.COMPLETED)).isEqualTo(ReversalAction.REVERSE);
    }

    @Test
    void aReversedPaymentIsLeftAlone() {
        assertThat(ReversalAction.forPayment(PaymentStatus.REVERSED)).isEqualTo(ReversalAction.ALREADY_REVERSED);
    }

    @Test
    void aPendingPaymentMakesTheEventWaitBecauseTheLedgerMayBeAheadOfUs() {
        assertThat(ReversalAction.forPayment(PaymentStatus.PENDING)).isEqualTo(ReversalAction.WAIT_FOR_PAYMENT);
    }

    @Test
    void aFailedPaymentHasNothingToReverse() {
        assertThat(ReversalAction.forPayment(PaymentStatus.FAILED)).isEqualTo(ReversalAction.NOT_REVERSIBLE);
    }

    @Test
    void everyPaymentStatusIsCovered() {
        for (PaymentStatus status : PaymentStatus.values()) {
            assertThat(ReversalAction.forPayment(status)).isNotNull();
        }
    }

    @Test
    void onlyACompletedPaymentCanMoveToReversed_matchingTheDatabaseStateMachine() {
        for (PaymentStatus status : PaymentStatus.values()) {
            boolean reverses = ReversalAction.forPayment(status) == ReversalAction.REVERSE;
            assertThat(reverses).as(status.name()).isEqualTo(status.canMoveTo(PaymentStatus.REVERSED));
        }
    }
}