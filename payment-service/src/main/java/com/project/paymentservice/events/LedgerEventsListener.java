package com.project.paymentservice.events;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Reads the ledger's TransactionPosted events from Kafka. */
@Component
@ConditionalOnProperty(name = "payments.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class LedgerEventsListener {

    private final PaymentReversalHandler handler;

    public LedgerEventsListener(PaymentReversalHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(topics = "${payments.kafka.ledger-topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onLedgerEvent(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader("event-id");
        if (header == null) {
            throw new IllegalArgumentException("Ledger event without an event-id header at offset " + record.offset());
        }
        UUID eventId = UUID.fromString(new String(header.value(), StandardCharsets.UTF_8));
        handler.handle(eventId, record.value());
    }
}