package com.project.paymentservice.events;

import com.project.paymentservice.common.CorrelationContext;
import java.util.UUID;
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
        UUID eventId = EventHeaders.eventId(record);
        try (CorrelationContext ignored = CorrelationContext.open(EventHeaders.correlationId(record, eventId))) {
            reversals.handle(eventId, record.value());
        }
    }
}