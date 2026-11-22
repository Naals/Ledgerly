package com.project.paymentservice.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.project.paymentservice.common.CorrelationContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class EventCorrelationTest {

    @Test
    void aLedgerListenerRestoresTheParentAfterHandlingAndAfterAnException() {
        PaymentReversalHandler handler = mock(PaymentReversalHandler.class);
        LedgerEventsListener listener = new LedgerEventsListener(handler);
        UUID eventId = UUID.randomUUID();
        ConsumerRecord<String, String> record = record(eventId, "originating-request");
        List<String> seen = new ArrayList<>();
        doAnswer(invocation -> {
            seen.add(CorrelationContext.current());
            if (seen.size() == 2) {
                throw new IllegalStateException("retry me");
            }
            return null;
        }).when(handler).handle(eventId, "{}");

        try (CorrelationContext ignored = CorrelationContext.open("worker-parent")) {
            listener.onLedgerEvent(record);
            assertThat(CorrelationContext.current()).isEqualTo("worker-parent");
            assertThatThrownBy(() -> listener.onLedgerEvent(record)).isInstanceOf(IllegalStateException.class);
            assertThat(CorrelationContext.current()).isEqualTo("worker-parent");
        }
        assertThat(seen).containsExactly("originating-request", "originating-request");
    }

    @Test
    void aHoldListenerFallsBackToEventIdentityAndNeverLeaksOneRecordsContextIntoTheNext() {
        PaymentHoldEventHandler handler = mock(PaymentHoldEventHandler.class);
        HoldEventsListener listener = new HoldEventsListener(handler);
        List<String> seen = new ArrayList<>();
        doAnswer(invocation -> {
            seen.add(CorrelationContext.current());
            return null;
        }).when(handler).handle(any(), anyString());
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID fourth = UUID.randomUUID();

        listener.onHoldEvent(record(first, "safe-request"));
        listener.onHoldEvent(record(second, "unsafe\nvalue"));
        listener.onHoldEvent(record(third, "x".repeat(65)));
        listener.onHoldEvent(record(fourth, null));

        assertThat(seen).containsExactly("safe-request", second.toString(), third.toString(), fourth.toString());
        assertThat(CorrelationContext.current()).isNull();
    }

    @Test
    void aMalformedEventIdentityStillFailsBeforeBusinessHandling() {
        PaymentReversalHandler handler = mock(PaymentReversalHandler.class);
        LedgerEventsListener listener = new LedgerEventsListener(handler);
        ConsumerRecord<String, String> missing = new ConsumerRecord<>("ledger", 0, 1, "key", "{}");
        try (CorrelationContext ignored = CorrelationContext.open("parent")) {
            assertThatThrownBy(() -> listener.onLedgerEvent(missing)).isInstanceOf(IllegalArgumentException.class);
            assertThat(CorrelationContext.current()).isEqualTo("parent");
        }
        verifyNoInteractions(handler);
    }

    @Test
    void invalidUtf8AndNullHeaderValuesUseTheStableFallback() {
        UUID eventId = UUID.randomUUID();
        ConsumerRecord<String, String> record = record(eventId, null);
        record.headers().add("correlation-id", new byte[] {(byte) 0xff});
        assertThat(EventHeaders.correlationId(record, eventId)).isEqualTo(eventId.toString());
        record.headers().add("correlation-id", null);
        assertThat(EventHeaders.correlationId(record, eventId)).isEqualTo(eventId.toString());
    }

    @Test
    void deadLettersWithMalformedIdentityRemainObservableUnderAStableContext() {
        EventMetrics metrics = mock(EventMetrics.class);
        DeadLetterMonitor listener = new DeadLetterMonitor(metrics);
        List<String> seen = new ArrayList<>();
        doAnswer(invocation -> {
            seen.add(CorrelationContext.current());
            return null;
        }).when(metrics).deadLettered(anyString());
        ConsumerRecord<String, String> record = new ConsumerRecord<>("ledger.DLT", 2, 44, "key", "{}");
        record.headers().add("event-id", "invalid".getBytes(StandardCharsets.UTF_8));
        try (CorrelationContext ignored = CorrelationContext.open("parent")) {
            listener.onDeadLetter(record);
            listener.onDeadLetter(record);
            assertThat(CorrelationContext.current()).isEqualTo("parent");
        }
        assertThat(seen).hasSize(2);
        assertThat(seen.get(0)).isEqualTo(seen.get(1));
        assertThat(CorrelationContext.isValid(seen.get(0))).isTrue();
        assertThat(seen.get(0)).isNotEqualTo("parent");
    }

    private static ConsumerRecord<String, String> record(UUID eventId, String correlationId) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("ledger", 0, 1, "key", "{}");
        record.headers().add("event-id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        if (correlationId != null) {
            record.headers().add("correlation-id", correlationId.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
