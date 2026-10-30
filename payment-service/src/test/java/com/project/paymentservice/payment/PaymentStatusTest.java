package com.project.paymentservice.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PaymentStatusTest {

    @Test
    void aPendingPaymentCanCompleteOrFail() {
        assertThat(PaymentStatus.PENDING.canMoveTo(PaymentStatus.COMPLETED)).isTrue();
        assertThat(PaymentStatus.PENDING.canMoveTo(PaymentStatus.FAILED)).isTrue();
        assertThat(PaymentStatus.PENDING.canMoveTo(PaymentStatus.REVERSED)).isFalse();
    }

    @Test
    void aCompletedPaymentCanOnlyBeReversed() {
        assertThat(PaymentStatus.COMPLETED.canMoveTo(PaymentStatus.REVERSED)).isTrue();
        assertThat(PaymentStatus.COMPLETED.canMoveTo(PaymentStatus.PENDING)).isFalse();
        assertThat(PaymentStatus.COMPLETED.canMoveTo(PaymentStatus.FAILED)).isFalse();
    }

    @Test
    void failedAndReversedPaymentsAreFinal() {
        for (PaymentStatus target : PaymentStatus.values()) {
            assertThat(PaymentStatus.FAILED.canMoveTo(target)).isFalse();
            assertThat(PaymentStatus.REVERSED.canMoveTo(target)).isFalse();
        }
    }

    @Test
    void noStatusMovesToItself() {
        for (PaymentStatus status : PaymentStatus.values()) {
            assertThat(status.canMoveTo(status)).isFalse();
        }
    }

    @Test
    void exactlyThreeMovesExist() {
        int moves = 0;
        for (PaymentStatus from : PaymentStatus.values()) {
            for (PaymentStatus to : PaymentStatus.values()) {
                if (from.canMoveTo(to)) {
                    moves++;
                }
            }
        }
        assertThat(moves).isEqualTo(3);
    }
}