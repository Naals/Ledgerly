package com.project.paymentservice.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.paymentservice.common.ServiceUnavailableException;
import com.project.paymentservice.ledger.HoldInfo;
import com.project.paymentservice.ledger.HoldState;
import com.project.paymentservice.ledger.LedgerOutcome;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentCancelIT extends AbstractTwoStepIT {

    @Test
    void cancellingReleasesTheHoldAndCancelsThePayment() {
        Payment authorized = authorized();

        Payment cancelled = payments.cancel(authorized.id());

        assertThat(cancelled.status()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(cancelled.ledgerTransactionId()).isNull();
        assertThat(ledger.holdCalls()).containsExactly("release");
    }

    @Test
    void cancellingTwiceChangesNothingAndDoesNotAskTheLedgerAgain() {
        Payment authorized = authorized();
        payments.cancel(authorized.id());

        assertThat(payments.cancel(authorized.id()).status()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(ledger.holdCalls()).containsExactly("release");
    }

    @Test
    void aCapturedPaymentCannotBeCancelled() {
        Payment authorized = authorized();
        payments.capture(authorized.id());
        ledger.reset();

        assertThatThrownBy(() -> payments.cancel(authorized.id())).isInstanceOf(PaymentNotCancellableException.class);
        assertThat(ledger.holdCalls()).isEmpty();
    }

    @Test
    void aCancelThatLostARaceToACaptureTheServiceNeverHeardAboutFindsOutAndSaysSo() {
        Payment authorized = authorized();
        ledger.releaseWith(payment -> new LedgerOutcome.Rejected<>("hold-not-active"));
        ledger.holdIs(holdId -> new LedgerOutcome.Done<>(
                new HoldInfo(holdId, HoldState.CAPTURED, OffsetDateTime.now().plusHours(1), UUID.randomUUID())));

        assertThatThrownBy(() -> payments.cancel(authorized.id()))
                .isInstanceOf(PaymentNotCancellableException.class).hasMessageContaining("captured");

        assertThat(payments.get(authorized.id()).status()).isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    void cancellingAHoldThatExpiredOnItsOwnLeavesTheExpiredPayment() {
        Payment authorized = authorized();
        ledger.releaseWith(payment -> new LedgerOutcome.Done<>(hold(payment, HoldState.EXPIRED, null)));

        assertThat(payments.cancel(authorized.id()).status()).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test
    void anUnavailableLedgerLeavesTheCancellationToBeRepeated() {
        Payment authorized = authorized();
        ledger.releaseWith(payment -> new LedgerOutcome.Unavailable<>("down"));

        assertThatThrownBy(() -> payments.cancel(authorized.id())).isInstanceOf(ServiceUnavailableException.class);

        assertThat(payments.get(authorized.id()).status()).isEqualTo(PaymentStatus.AUTHORIZED);
    }
}