package com.project.paymentservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventRecordSpecTest {

    private static final Map<String, String> TOPICS = Map.of("PaymentStatusChanged", "payments.payment-status-changed.v1");

    private final UUID paymentId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final OutboxEvent event =
            new OutboxEvent(7, eventId, "Payment", paymentId, "PaymentStatusChanged", "{\"status\":\"COMPLETED\"}", 0);

    @Test
    void sendsTheEventToTheTopicOfItsType() {
        assertThat(EventRecordSpec.of(event, TOPICS).topic()).isEqualTo("payments.payment-status-changed.v1");
    }

    @Test
    void keysTheRecordByThePaymentSoItsEventsStayInOnePartitionInOrder() {
        assertThat(EventRecordSpec.of(event, TOPICS).key()).isEqualTo(paymentId.toString());
    }

    @Test
    void sendsThePayloadAsItIsAndPutsTheIdentityInHeaders() {
        EventRecordSpec spec = EventRecordSpec.of(event, TOPICS);

        assertThat(spec.value()).isEqualTo("{\"status\":\"COMPLETED\"}");
        assertThat(spec.headers()).containsEntry("event-id", eventId.toString())
                .containsEntry("event-type", "PaymentStatusChanged")
                .containsEntry("aggregate-type", "Payment");
    }

    @Test
    void refusesAnEventTypeThatHasNoTopic() {
        assertThatThrownBy(() -> EventRecordSpec.of(event, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PaymentStatusChanged");
    }
}