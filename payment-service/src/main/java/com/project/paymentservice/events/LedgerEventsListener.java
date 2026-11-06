package com.project.paymentservice.events;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Reads the ledger's TransactionPosted events from Kafka. Today only reversals matter to this service. */
@Component
@ConditionalOnProperty(name = "payments.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class LedgerEventsListener {

    private final PaymentReversalHandler reversals;

    public LedgerEventsListener(PaymentReversalHandler reversals) {
        this.reversals = reversals;
    }

    @KafkaListener(topics = "${payments.kafka.ledger-topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onLedgerEvent(ConsumerRecord<String, String> record) {
        reversals.handle(EventHeaders.eventId(record), record.value());
    }
}