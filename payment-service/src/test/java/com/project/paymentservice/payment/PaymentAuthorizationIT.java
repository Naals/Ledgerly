package com.project.paymentservice.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.project.paymentservice.ledger.HoldInfo;
import com.project.paymentservice.ledger.HoldState;
import com.project.paymentservice.ledger.LedgerOutcome;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentAuthorizationIT extends AbstractTwoStepIT {

    @Test
    void aTwoStepPaymentIsAuthorizedAndRemembersItsHoldAndExpiry() {
        HoldInfo placed = new HoldInfo(UUID.randomUUID(), HoldState.ACTIVE, OffsetDateTime.now().plusHours(5), null);
        ledger.authorizeWith(payment -> new LedgerOutcome.Done<>(placed));

        Payment payment = payments.create(newKey(), manual()).payment();

        assertThat(payment.status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payment.captureMode()).isEqualTo(CaptureMode.MANUAL);
        assertThat(payment.ledgerHoldId()).isEqualTo(placed.id());
        assertThat(payment.authorizationExpiresAt().toInstant())
                .isCloseTo(placed.expiresAt().toInstant(), within(1, ChronoUnit.MILLIS));
        assertThat(payment.ledgerTransactionId()).isNull();
        assertThat(ledger.holdCalls()).containsExactly("authorize");
        assertThat(ledger.calls()).isEmpty();
    }

    @Test
    void aOneStepPaymentNeverTouchesHolds() {
        Payment payment = payments.create(newKey(),
                new CreatePayment(UUID.randomUUID(), UUID.randomUUID(), 5_000, "KZT")).payment();

        assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.captureMode()).isEqualTo(CaptureMode.AUTOMATIC);
        assertThat(ledger.holdCalls()).isEmpty();
        assertThat(ledger.calls()).hasSize(1);
    }

    @Test
    void aRefusedAuthorizationFailsThePaymentWithTheLedgersCode() {
        ledger.authorizeWith(payment -> new LedgerOutcome.Rejected<>("insufficient-funds"));

        Payment payment = payments.create(newKey(), manual()).payment();

        assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.failureCode()).isEqualTo("insufficient-funds");
        assertThat(payment.ledgerHoldId()).isNull();
    }

    @Test
    void anUnavailableLedgerLeavesTheAuthorizationPendingUntilItIsExecutedAgain() {
        ledger.authorizeWith(payment -> new LedgerOutcome.Unavailable<>("down"));
        Payment pending = payments.create(newKey(), manual()).payment();
        assertThat(pending.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(pending.attempts()).isEqualTo(1);

        ledger.reset();

        assertThat(payments.execute(pending.id()).status()).isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    void replayingAnAuthorizedPaymentDoesNotAskTheLedgerAgain() {
        String key = newKey();
        CreatePayment command = manual();
        payments.create(key, command);

        CreatedPayment replay = payments.create(key, command);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.payment().status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(ledger.holdCalls()).containsExactly("authorize");
    }

    @Test
    void theSameKeyForAOneStepAndATwoStepPaymentIsAReuseError() {
        String key = newKey();
        CreatePayment oneStep = new CreatePayment(UUID.randomUUID(), UUID.randomUUID(), 100_000, "KZT");
        payments.create(key, oneStep);
        CreatePayment twoStep = new CreatePayment(
                oneStep.fromAccountId(), oneStep.toAccountId(), oneStep.amountMinor(), "KZT", CaptureMode.MANUAL);

        assertThatThrownBy(() -> payments.create(key, twoStep)).isInstanceOf(IdempotencyKeyReuseException.class);
    }
}