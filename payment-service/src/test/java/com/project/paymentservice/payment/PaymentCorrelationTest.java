package com.project.paymentservice.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.project.paymentservice.common.CorrelationContext;
import com.project.paymentservice.ledger.HoldInfo;
import com.project.paymentservice.ledger.HoldState;
import com.project.paymentservice.ledger.LedgerGateway;
import com.project.paymentservice.ledger.LedgerOutcome;
import com.project.paymentservice.ledger.LedgerResult;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class PaymentCorrelationTest {

    private final PaymentRepository repository = mock(PaymentRepository.class);
    private final LedgerGateway ledger = mock(LedgerGateway.class);
    private final PaymentService service = new PaymentService(repository, ledger);

    @AfterEach
    void clearContext() {
        MDC.remove(CorrelationContext.MDC_KEY);
    }

    @Test
    void backgroundRetriesUseEachPaymentsIdentityWithoutLeakingToTheNextPayment() {
        Payment first = payment(CaptureMode.AUTOMATIC, PaymentStatus.PENDING);
        Payment second = payment(CaptureMode.AUTOMATIC, PaymentStatus.PENDING);
        when(repository.findStalePending(30, 50)).thenReturn(List.of(first.id(), second.id()));
        when(repository.findById(first.id())).thenReturn(Optional.of(first));
        when(repository.findById(second.id())).thenReturn(Optional.of(second));
        List<String> observed = new ArrayList<>();
        when(ledger.transfer(any())).thenAnswer(invocation -> {
            observed.add(CorrelationContext.current());
            return new LedgerResult.Unavailable("try later");
        });

        assertThat(service.retryPending(30, 50)).isEqualTo(2);

        assertThat(observed).containsExactly(first.id().toString(), second.id().toString());
        assertThat(CorrelationContext.current()).isNull();
    }

    @Test
    void reconciliationUsesThePaymentIdForBothLedgerLookupAndStatusWrite() {
        Payment payment = payment(CaptureMode.MANUAL, PaymentStatus.AUTHORIZED);
        when(ledger.findHold(payment.ledgerHoldId())).thenAnswer(invocation -> {
            assertThat(CorrelationContext.current()).isEqualTo(payment.id().toString());
            return new LedgerOutcome.Done<>(new HoldInfo(payment.ledgerHoldId(), HoldState.RELEASED,
                    payment.authorizationExpiresAt(), null));
        });
        when(repository.markCancelled(payment.id())).thenAnswer(invocation -> {
            assertThat(CorrelationContext.current()).isEqualTo(payment.id().toString());
            return true;
        });

        assertThat(service.settle(payment)).isEqualTo(Settlement.SETTLED);
        assertThat(CorrelationContext.current()).isNull();
    }

    @Test
    void callerContextSurvivesANestedSettlementAfterARefusedCapture() {
        Payment payment = payment(CaptureMode.MANUAL, PaymentStatus.AUTHORIZED);
        when(repository.findById(payment.id())).thenReturn(Optional.of(payment));
        when(ledger.capture(payment)).thenAnswer(invocation -> {
            assertThat(CorrelationContext.current()).isEqualTo("request-capture-42");
            return new LedgerOutcome.Rejected<>("hold-not-active");
        });
        when(ledger.findHold(payment.ledgerHoldId())).thenAnswer(invocation -> {
            assertThat(CorrelationContext.current()).isEqualTo("request-capture-42");
            return new LedgerOutcome.Done<>(new HoldInfo(payment.ledgerHoldId(), HoldState.RELEASED,
                    payment.authorizationExpiresAt(), null));
        });
        try (var correlation = CorrelationContext.open("request-capture-42")) {
            assertThatThrownBy(() -> service.capture(payment.id()))
                    .isInstanceOf(PaymentNotCapturableException.class);
            assertThat(CorrelationContext.current()).isEqualTo("request-capture-42");
        }
        assertThat(CorrelationContext.current()).isNull();
    }

    @Test
    void aFailedGatewayCallRestoresThePreviousThreadContext() {
        Payment payment = payment(CaptureMode.AUTOMATIC, PaymentStatus.PENDING);
        when(repository.findById(payment.id())).thenReturn(Optional.of(payment));
        MDC.put(CorrelationContext.MDC_KEY, "unsafe previous context");
        when(ledger.transfer(payment)).thenAnswer(invocation -> {
            assertThat(CorrelationContext.current()).isEqualTo(payment.id().toString());
            throw new IllegalStateException("gateway failed");
        });

        assertThatThrownBy(() -> service.execute(payment.id())).hasMessage("gateway failed");
        assertThat(MDC.get(CorrelationContext.MDC_KEY)).isEqualTo("unsafe previous context");
    }

    private static Payment payment(CaptureMode mode, PaymentStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return new Payment(UUID.randomUUID(), "key", "hash", UUID.randomUUID(), UUID.randomUUID(),
                100, "KZT", mode, status, null, null, UUID.randomUUID(), now.plusHours(1), 0, now, now);
    }
}
