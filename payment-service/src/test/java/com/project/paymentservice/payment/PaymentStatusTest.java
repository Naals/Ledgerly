package com.project.paymentservice.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaymentStatusTest {

    @Test
    void aOneStepPaymentGoesStraightFromPendingToCompletedOrFailed() {
        assertThat(PaymentStatus.PENDING.canMoveTo(PaymentStatus.COMPLETED, CaptureMode.AUTOMATIC)).isTrue();
        assertThat(PaymentStatus.PENDING.canMoveTo(PaymentStatus.FAILED, CaptureMode.AUTOMATIC)).isTrue();
        assertThat(PaymentStatus.PENDING.canMoveTo(PaymentStatus.AUTHORIZED, CaptureMode.AUTOMATIC)).isFalse();
    }

    @Test
    void aTwoStepPaymentMustBeAuthorizedBeforeItCanBeCompleted() {
        assertThat(PaymentStatus.PENDING.canMoveTo(PaymentStatus.AUTHORIZED, CaptureMode.MANUAL)).isTrue();
        assertThat(PaymentStatus.PENDING.canMoveTo(PaymentStatus.FAILED, CaptureMode.MANUAL)).isTrue();
        assertThat(PaymentStatus.PENDING.canMoveTo(PaymentStatus.COMPLETED, CaptureMode.MANUAL)).isFalse();
    }

    @Test
    void anAuthorizationEndsInExactlyOneOfThreeWays() {
        for (PaymentStatus target : PaymentStatus.values()) {
            boolean expected = Set.of(PaymentStatus.COMPLETED, PaymentStatus.CANCELLED, PaymentStatus.EXPIRED)
                    .contains(target);
            assertThat(PaymentStatus.AUTHORIZED.canMoveTo(target, CaptureMode.MANUAL)).as("AUTHORIZED -> " + target)
                    .isEqualTo(expected);
        }
    }

    @Test
    void onlyACompletedPaymentCanBeReversedAndNothingLeavesTheOtherEndStates() {
        assertThat(PaymentStatus.COMPLETED.canMoveTo(PaymentStatus.REVERSED)).isTrue();
        for (PaymentStatus end : EnumSet.of(PaymentStatus.FAILED, PaymentStatus.CANCELLED,
                PaymentStatus.EXPIRED, PaymentStatus.REVERSED)) {
            for (PaymentStatus target : PaymentStatus.values()) {
                assertThat(end.canMoveTo(target)).as(end + " -> " + target).isFalse();
            }
        }
    }

    @Test
    void onlyATwoStepPaymentIsEverAuthorizedCancelledOrExpired() {
        for (PaymentStatus status : EnumSet.of(PaymentStatus.AUTHORIZED, PaymentStatus.CANCELLED, PaymentStatus.EXPIRED)) {
            assertThat(status.isPossibleFor(CaptureMode.MANUAL)).isTrue();
            assertThat(status.isPossibleFor(CaptureMode.AUTOMATIC)).isFalse();
        }
        for (PaymentStatus status : EnumSet.of(PaymentStatus.PENDING, PaymentStatus.COMPLETED,
                PaymentStatus.FAILED, PaymentStatus.REVERSED)) {
            assertThat(status.isPossibleFor(CaptureMode.AUTOMATIC)).isTrue();
            assertThat(status.isPossibleFor(CaptureMode.MANUAL)).isTrue();
        }
    }
}