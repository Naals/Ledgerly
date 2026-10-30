package com.project.ledgercore.outbox;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes outbox events to Kafka and waits for the broker's acknowledgement before returning, so the relay
 * only marks an event published once Kafka has really accepted it. Any failure is an exception, which makes
 * the relay retry the event later.
 */
@Component
@ConditionalOnProperty(name = "ledger.outbox.publisher", havingValue = "kafka")
public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final KafkaEventProperties properties;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafka, KafkaEventProperties properties) {
        this.kafka = kafka;
        this.properties = properties;
    }

    @Override
    public void publish(OutboxEvent event) {
        EventRecordSpec spec = EventRecordSpec.of(event, properties.getTopics());
        ProducerRecord<String, String> record = new ProducerRecord<>(spec.topic(), spec.key(), spec.value());
        spec.headers().forEach((name, value) ->
                record.headers().add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8))));
        try {
            kafka.send(record).get(properties.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing event " + event.id(), e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            throw new IllegalStateException("Kafka did not confirm event " + event.id(), e);
        }
    }
}