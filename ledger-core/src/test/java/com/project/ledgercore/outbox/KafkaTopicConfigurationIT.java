package com.project.ledgercore.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Exercise the shipped configuration; publisher-specific tests supply their own topic maps. */
class KafkaTopicConfigurationIT extends AbstractIntegrationTest {

    @Autowired
    KafkaEventProperties properties;

    @Test
    void theDefaultConfigurationRoutesBothKindsOfLedgerEvent() {
        assertThat(route("TransactionPosted")).isEqualTo("ledger.transaction-posted.v1");
        assertThat(route("HoldStatusChanged")).isEqualTo("ledger.hold-status-changed.v1");
    }

    private String route(String eventType) {
        OutboxEvent event = new OutboxEvent(1, UUID.randomUUID(), "test", UUID.randomUUID(), eventType, "{}", 0);
        return EventRecordSpec.of(event, properties.getTopics()).topic();
    }
}
