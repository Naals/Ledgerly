package com.project.paymentservice.events;

import com.project.paymentservice.common.CorrelationContext;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Reads the ledger's HoldStatusChanged events from Kafka. */
@Component
@ConditionalOnProperty(name = "payments.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class HoldEventsListener {

    private final PaymentHoldEventHandler handler;

    public HoldEventsListener(PaymentHoldEventHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(topics = "${payments.kafka.hold-topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onHoldEvent(ConsumerRecord<String, String> record) {
        UUID eventId = EventHeaders.eventId(record);
        try (CorrelationContext ignored = CorrelationContext.open(EventHeaders.correlationId(record, eventId))) {
            handler.handle(eventId, record.value());
        }
    }
}