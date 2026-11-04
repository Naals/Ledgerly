package com.project.paymentservice.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.paymentservice.common.BusinessRuleException;
import com.project.paymentservice.common.ServiceUnavailableException;
import com.project.paymentservice.ledger.HoldInfo;
import com.project.paymentservice.ledger.HoldState;
import com.project.paymentservice.ledger.LedgerOutcome;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentCaptureIT extends AbstractTwoStepIT {

    @Test
    void capturingMovesTheMoneyAndCompletesThePayment() {
        Payment authorized = authorized();
        UUID transaction = UUID.randomUUID();
        ledger.captureWith(payment -> new LedgerOutcome.Done<>(transaction));

        Payment captured = payments.capture(authorized.id());

        assertThat(captured.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(captured.ledgerTransactionId()).isEqualTo(transaction);
        assertThat(captured.ledgerHoldId()).isEqualTo(authorized.ledgerHoldId());
        assertThat(ledger.holdCalls()).containsExactly("capture");
    }

    @Test
    void capturingTwiceChangesNothingAndDoesNotAskTheLedgerAgain() {
        Payment authorized = authorized();
        Payment first = payments.capture(authorized.id());

        Payment second = payments.capture(authorized.id());

        assertThat(second.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(second.ledgerTransactionId()).isEqualTo(first.ledgerTransactionId());
        assertThat(ledger.holdCalls()).containsExactly("capture");
    }

    @Test
    void aOneStepPaymentCannotBeCaptured() {
        Payment payment = payments.create(newKey(),
                new CreatePayment(UUID.randomUUID(), UUID.randomUUID(), 5_000, "KZT")).payment();
        ledger.reset();

        assertThatThrownBy(() -> payments.capture(payment.id())).isInstanceOf(PaymentNotCapturableException.class);
        assertThat(ledger.holdCalls()).isEmpty();
    }

    @Test
    void aPaymentThatIsNotYetAuthorizedCannotBeCaptured() {
        ledger.authorizeWith(payment -> new LedgerOutcome.Unavailable<>("down"));
        Payment pending = payments.create(newKey(), manual()).payment();
        ledger.reset();

        assertThatThrownBy(() -> payments.capture(pending.id()))
                .isInstanceOf(PaymentNotCapturableException.class).hasMessageContaining("PENDING");
        assertThat(ledger.holdCalls()).isEmpty();
    }

    @Test
    void anUnavailableLedgerLeavesThePaymentAuthorizedSoTheCaptureCanBeRepeated() {
        Payment authorized = authorized();
        ledger.captureWith(payment -> new LedgerOutcome.Unavailable<>("timeout"));

        assertThatThrownBy(() -> payments.capture(authorized.id()))
                .isInstanceOf(ServiceUnavailableException.class).extracting("code").isEqualTo("ledger-unavailable");
        assertThat(payments.get(authorized.id()).status()).isEqualTo(PaymentStatus.AUTHORIZED);

        ledger.reset();
        assertThat(payments.capture(authorized.id()).status()).isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    void aHoldThatRanOutExpiresThePayment() {
        Payment authorized = authorized();
        ledger.captureWith(payment -> new LedgerOutcome.Rejected<>("hold-expired"));

        assertThatThrownBy(() -> payments.capture(authorized.id())).isInstanceOf(PaymentNotCapturableException.class);

        assertThat(payments.get(authorized.id()).status()).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test
    void aRefusedCaptureWhoseHoldWasInFactCapturedBeforeCompletesThePayment() {
        // the first capture reached the ledger but its answer was lost: the ledger now refuses a second one
        Payment authorized = authorized();
        UUID transaction = UUID.randomUUID();
        ledger.captureWith(payment -> new LedgerOutcome.Rejected<>("hold-not-active"));
        ledger.holdIs(holdId -> new LedgerOutcome.Done<>(
                new HoldInfo(holdId, HoldState.CAPTURED, OffsetDateTime.now().plusHours(1), transaction)));

        Payment payment = payments.capture(authorized.id());

        assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.ledgerTransactionId()).isEqualTo(transaction);
        assertThat(ledger.holdCalls()).containsExactly("capture", "findHold");
    }

    @Test
    void aRefusedCaptureWhoseHoldWasReleasedElsewhereCancelsThePayment() {
        Payment authorized = authorized();
        ledger.captureWith(payment -> new LedgerOutcome.Rejected<>("hold-not-active"));
        ledger.holdIs(holdId -> new LedgerOutcome.Done<>(
                new HoldInfo(holdId, HoldState.RELEASED, OffsetDateTime.now().plusHours(1), null)));

        assertThatThrownBy(() -> payments.capture(authorized.id()))
                .isInstanceOf(PaymentNotCapturableException.class).hasMessageContaining("CANCELLED");

        assertThat(payments.get(authorized.id()).status()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    void anyOtherRefusalIsPassedOnAndLeavesThePaymentAuthorized() {
        Payment authorized = authorized();
        ledger.captureWith(payment -> new LedgerOutcome.Rejected<>("account-not-active"));

        assertThatThrownBy(() -> payments.capture(authorized.id()))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("account-not-active");

        assertThat(payments.get(authorized.id()).status()).isEqualTo(PaymentStatus.AUTHORIZED);
    }
}