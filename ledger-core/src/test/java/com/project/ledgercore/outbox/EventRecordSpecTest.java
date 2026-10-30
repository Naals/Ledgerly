package com.project.ledgercore.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventRecordSpecTest {

    private static final Map<String, String> TOPICS = Map.of("TransactionPosted", "ledger.transaction-posted.v1");

    private final UUID eventId = UUID.randomUUID();
    private final UUID transactionId = UUID.randomUUID();

    private OutboxEvent event(String type) {
        return new OutboxEvent(42, eventId, "transaction", transactionId, type, "{\"version\": 1}", 0);
    }

    @Test
    void sendsTheEventToTheTopicConfiguredForItsType() {
        EventRecordSpec spec = EventRecordSpec.of(event("TransactionPosted"), TOPICS);

        assertThat(spec.topic()).isEqualTo("ledger.transaction-posted.v1");
        assertThat(spec.value()).isEqualTo("{\"version\": 1}");
    }

    @Test
    void usesTheAggregateIdAsTheKeySoOneTransactionStaysInOnePartition() {
        assertThat(EventRecordSpec.of(event("TransactionPosted"), TOPICS).key()).isEqualTo(transactionId.toString());
    }

    @Test
    void carriesTheEventIdTypeAndAggregateTypeAsHeaders() {
        EventRecordSpec spec = EventRecordSpec.of(event("TransactionPosted"), TOPICS);

        assertThat(spec.headers()).containsOnly(
                Map.entry("event-id", eventId.toString()),
                Map.entry("event-type", "TransactionPosted"),
                Map.entry("aggregate-type", "transaction"));
    }

    @Test
    void anEventTypeWithoutATopicIsAnErrorNotASilentDrop() {
        assertThatThrownBy(() -> EventRecordSpec.of(event("SomethingElse"), TOPICS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SomethingElse");
    }
}