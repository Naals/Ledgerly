package com.project.paymentservice.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.paymentservice.ledger.HoldState;
import com.project.paymentservice.payment.PaymentStatus;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HoldEventActionTest {

    private static final Set<PaymentStatus> DEAD = EnumSet.of(PaymentStatus.FAILED, PaymentStatus.CANCELLED, PaymentStatus.EXPIRED);
    private static final Set<PaymentStatus> PAID = EnumSet.of(PaymentStatus.COMPLETED, PaymentStatus.REVERSED);

    @Test
    void aCapturedHoldCompletesAnAuthorizedPaymentAndContradictsADeadOne() {
        Map<PaymentStatus, HoldEventAction> expected = Map.of(
                PaymentStatus.PENDING, HoldEventAction.WAIT_FOR_AUTHORIZATION,
                PaymentStatus.AUTHORIZED, HoldEventAction.COMPLETE,
                PaymentStatus.COMPLETED, HoldEventAction.ALREADY_APPLIED,
                PaymentStatus.REVERSED, HoldEventAction.ALREADY_APPLIED,
                PaymentStatus.FAILED, HoldEventAction.INCONSISTENT,
                PaymentStatus.CANCELLED, HoldEventAction.INCONSISTENT,
                PaymentStatus.EXPIRED, HoldEventAction.INCONSISTENT);

        assertRow(HoldState.CAPTURED, expected);
    }

    @Test
    void aReleasedHoldCancelsAnAuthorizedPaymentAndContradictsAPaidOne() {
        Map<PaymentStatus, HoldEventAction> expected = Map.of(
                PaymentStatus.PENDING, HoldEventAction.WAIT_FOR_AUTHORIZATION,
                PaymentStatus.AUTHORIZED, HoldEventAction.CANCEL,
                PaymentStatus.CANCELLED, HoldEventAction.ALREADY_APPLIED,
                PaymentStatus.EXPIRED, HoldEventAction.ALREADY_APPLIED,
                PaymentStatus.FAILED, HoldEventAction.ALREADY_APPLIED,
                PaymentStatus.COMPLETED, HoldEventAction.INCONSISTENT,
                PaymentStatus.REVERSED, HoldEventAction.INCONSISTENT);

        assertRow(HoldState.RELEASED, expected);
    }

    @Test
    void anExpiredHoldExpiresAnAuthorizedPaymentAndContradictsAPaidOne() {
        Map<PaymentStatus, HoldEventAction> expected = Map.of(
                PaymentStatus.PENDING, HoldEventAction.WAIT_FOR_AUTHORIZATION,
                PaymentStatus.AUTHORIZED, HoldEventAction.EXPIRE,
                PaymentStatus.CANCELLED, HoldEventAction.ALREADY_APPLIED,
                PaymentStatus.EXPIRED, HoldEventAction.ALREADY_APPLIED,
                PaymentStatus.FAILED, HoldEventAction.ALREADY_APPLIED,
                PaymentStatus.COMPLETED, HoldEventAction.INCONSISTENT,
                PaymentStatus.REVERSED, HoldEventAction.INCONSISTENT);

        assertRow(HoldState.EXPIRED, expected);
    }

    @Test
    void anActiveHoldIsNeverActedOn() {
        for (PaymentStatus payment : PaymentStatus.values()) {
            assertThat(HoldEventAction.decide(HoldState.ACTIVE, payment)).as(payment.name())
                    .isEqualTo(HoldEventAction.NOTHING_TO_DO);
        }
    }

    @Test
    void onlyAnAuthorizedPaymentIsEverChanged() {
        for (HoldState hold : HoldState.values()) {
            for (PaymentStatus payment : PaymentStatus.values()) {
                HoldEventAction action = HoldEventAction.decide(hold, payment);
                boolean changes = action == HoldEventAction.COMPLETE || action == HoldEventAction.CANCEL
                        || action == HoldEventAction.EXPIRE;
                assertThat(changes).as(hold + " / " + payment)
                        .isEqualTo(payment == PaymentStatus.AUTHORIZED && hold != HoldState.ACTIVE);
            }
        }
    }

    @Test
    void aContradictionAboutMoneyIsAlwaysRaisedAndNothingElseIs() {
        for (HoldState hold : HoldState.values()) {
            for (PaymentStatus payment : PaymentStatus.values()) {
                boolean moneyMovedForADeadPayment = hold == HoldState.CAPTURED && DEAD.contains(payment);
                boolean paidButTheHoldWasGivenBack =
                        (hold == HoldState.RELEASED || hold == HoldState.EXPIRED) && PAID.contains(payment);

                assertThat(HoldEventAction.decide(hold, payment) == HoldEventAction.INCONSISTENT)
                        .as(hold + " / " + payment)
                        .isEqualTo(moneyMovedForADeadPayment || paidButTheHoldWasGivenBack);
            }
        }
    }

    @Test
    void aPendingPaymentAlwaysWaitsForAnOutcome() {
        for (HoldState hold : EnumSet.of(HoldState.CAPTURED, HoldState.RELEASED, HoldState.EXPIRED)) {
            assertThat(HoldEventAction.decide(hold, PaymentStatus.PENDING)).as(hold.name())
                    .isEqualTo(HoldEventAction.WAIT_FOR_AUTHORIZATION);
        }
    }

    private static void assertRow(HoldState hold, Map<PaymentStatus, HoldEventAction> expected) {
        assertThat(expected).as("the table covers every payment status").hasSize(PaymentStatus.values().length);
        for (PaymentStatus payment : PaymentStatus.values()) {
            assertThat(HoldEventAction.decide(hold, payment)).as(hold + " / " + payment).isEqualTo(expected.get(payment));
        }
    }
}