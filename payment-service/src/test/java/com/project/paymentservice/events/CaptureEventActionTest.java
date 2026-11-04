package com.project.paymentservice.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.paymentservice.payment.PaymentStatus;
import org.junit.jupiter.api.Test;

class CaptureEventActionTest {

    @Test
    void anAuthorizedPaymentIsCompletedByItsCapture() {
        assertThat(CaptureEventAction.forPayment(PaymentStatus.AUTHORIZED)).isEqualTo(CaptureEventAction.COMPLETE);
    }

    @Test
    void aPaymentThatAlreadyKnowsItWasCapturedIsLeftAlone() {
        assertThat(CaptureEventAction.forPayment(PaymentStatus.COMPLETED)).isEqualTo(CaptureEventAction.ALREADY_COMPLETED);
        assertThat(CaptureEventAction.forPayment(PaymentStatus.REVERSED)).isEqualTo(CaptureEventAction.ALREADY_COMPLETED);
    }

    @Test
    void aPendingPaymentMakesTheEventWaitUntilItsAuthorizationIsRecorded() {
        assertThat(CaptureEventAction.forPayment(PaymentStatus.PENDING)).isEqualTo(CaptureEventAction.WAIT_FOR_AUTHORIZATION);
    }

    @Test
    void moneyMovedForADeadPaymentIsRaisedNotHidden() {
        for (PaymentStatus status : new PaymentStatus[] {PaymentStatus.FAILED, PaymentStatus.CANCELLED, PaymentStatus.EXPIRED}) {
            assertThat(CaptureEventAction.forPayment(status)).as(status.name()).isEqualTo(CaptureEventAction.INCONSISTENT);
        }
    }

    @Test
    void everyStatusIsCoveredAndOnlyAnAuthorizedPaymentIsCompletedByIt() {
        for (PaymentStatus status : PaymentStatus.values()) {
            boolean completes = CaptureEventAction.forPayment(status) == CaptureEventAction.COMPLETE;
            assertThat(completes).as(status.name()).isEqualTo(status == PaymentStatus.AUTHORIZED);
        }
    }
}