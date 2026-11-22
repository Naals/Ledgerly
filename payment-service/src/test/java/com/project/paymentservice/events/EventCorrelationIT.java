package com.project.paymentservice.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.paymentservice.common.CorrelationContext;
import com.project.paymentservice.outbox.OutboxEvent;
import com.project.paymentservice.outbox.OutboxRepository;
import com.project.paymentservice.payment.CaptureMode;
import com.project.paymentservice.payment.PaymentRepository;
import com.project.paymentservice.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class EventCorrelationIT extends AbstractIntegrationTest {

    @Autowired
    PaymentRepository payments;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    PaymentReversalHandler reversals;

    @Autowired
    PaymentHoldEventHandler holds;

    @Test
    void aReversalCarriesItsIncomingCorrelationIntoThePaymentOutbox() {
        UUID paymentId = pending(CaptureMode.AUTOMATIC);
        UUID transactionId = UUID.randomUUID();
        payments.markCompleted(paymentId, transactionId);
        ConsumerRecord<String, String> record = record(UUID.randomUUID(), "reversal-request", """
                {"version":1,"transactionId":"%s","type":"REVERSAL","reference":"%s",
                 "reversesTransactionId":"%s"}
                """.formatted(UUID.randomUUID(), paymentId, transactionId));

        try (CorrelationContext ignored = CorrelationContext.open("consumer-parent")) {
            new LedgerEventsListener(reversals).onLedgerEvent(record);
            assertThat(CorrelationContext.current()).isEqualTo("consumer-parent");
        }
        assertThat(outbox.findByAggregate(paymentId)).extracting(OutboxEvent::correlationId)
                .containsExactly(paymentId.toString(), "reversal-request");
    }

    @Test
    void aHoldEventWithoutCorrelationUsesItsIdentityForThePaymentStatusEvent() {
        UUID paymentId = pending(CaptureMode.MANUAL);
        UUID holdId = UUID.randomUUID();
        payments.markAuthorized(paymentId, holdId, OffsetDateTime.now().plusHours(1));
        UUID eventId = UUID.randomUUID();
        new HoldEventsListener(holds).onHoldEvent(record(eventId, null, captured(holdId, paymentId)));

        assertThat(outbox.findByAggregate(paymentId)).extracting(OutboxEvent::correlationId)
                .containsExactly(paymentId.toString(), eventId.toString());
        assertThat(CorrelationContext.current()).isNull();
    }

    @Test
    void aRetriedHoldEventKeepsItsContextWithoutLeakingItWhenHandlingRollsBack() {
        UUID paymentId = pending(CaptureMode.MANUAL);
        UUID holdId = UUID.randomUUID();
        ConsumerRecord<String, String> record = record(UUID.randomUUID(), "capture-request", captured(holdId, paymentId));
        HoldEventsListener listener = new HoldEventsListener(holds);
        try (CorrelationContext ignored = CorrelationContext.open("consumer-parent")) {
            assertThatThrownBy(() -> listener.onHoldEvent(record)).isInstanceOf(PaymentNotSettledException.class);
            assertThat(CorrelationContext.current()).isEqualTo("consumer-parent");
        }
        assertThat(outbox.findByAggregate(paymentId)).isEmpty();
        payments.markAuthorized(paymentId, holdId, OffsetDateTime.now().plusHours(1));
        listener.onHoldEvent(record);
        listener.onHoldEvent(record); // redelivery emits no duplicate status event

        assertThat(outbox.findByAggregate(paymentId)).extracting(OutboxEvent::correlationId)
                .containsExactly(paymentId.toString(), "capture-request");
        assertThat(CorrelationContext.current()).isNull();
    }

    private UUID pending(CaptureMode mode) {
        return payments.insertIfNew("correlation-" + UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(),
                UUID.randomUUID(), 100, "KZT", mode).orElseThrow();
    }

    private String captured(UUID holdId, UUID paymentId) {
        return """
                {"version":1,"holdId":"%s","status":"CAPTURED","reference":"%s","ledgerTransactionId":"%s"}
                """.formatted(holdId, paymentId, UUID.randomUUID());
    }

    private ConsumerRecord<String, String> record(UUID eventId, String correlationId, String payload) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("ledger", 0, 1, "key", payload);
        record.headers().add("event-id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        if (correlationId != null) {
            record.headers().add("correlation-id", correlationId.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
