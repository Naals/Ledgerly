package com.project.ledgercore.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
class KafkaEventPublisherIT {

    private static final String TOPIC = "ledger.transaction-posted.v1";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @Test
    void anEventIsDeliveredWithItsKeyHeadersAndPayload() {
        KafkaEventPublisher publisher = publisherFor(KAFKA.getBootstrapServers(), Duration.ofSeconds(15));
        OutboxEvent event = new OutboxEvent(1, UUID.randomUUID(), "transaction", UUID.randomUUID(),
                "TransactionPosted", "{\"version\": 1, \"type\": \"TRANSFER\"}", 0);

        publisher.publish(event);

        ConsumerRecord<String, String> record = firstRecordOf(TOPIC);
        assertThat(record.key()).isEqualTo(event.aggregateId().toString());
        assertThat(record.value()).isEqualTo(event.payload());
        assertThat(header(record, "event-id")).isEqualTo(event.id().toString());
        assertThat(header(record, "event-type")).isEqualTo("TransactionPosted");
        assertThat(header(record, "aggregate-type")).isEqualTo("transaction");
    }

    @Test
    void aBrokerThatCannotBeReachedMakesThePublishFail() {
        KafkaEventPublisher publisher = publisherFor("localhost:1", Duration.ofSeconds(5));
        OutboxEvent event = new OutboxEvent(2, UUID.randomUUID(), "transaction", UUID.randomUUID(),
                "TransactionPosted", "{\"version\": 1}", 0);

        assertThatThrownBy(() -> publisher.publish(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(event.id().toString());
    }

    private static KafkaEventPublisher publisherFor(String bootstrapServers, Duration sendTimeout) {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 2_000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2_000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 4_000);
        KafkaTemplate<String, String> template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));

        KafkaEventProperties properties = new KafkaEventProperties();
        properties.setTopics(Map.of("TransactionPosted", TOPIC));
        properties.setSendTimeout(sendTimeout);
        return new KafkaEventPublisher(template, properties);
    }

    private static ConsumerRecord<String, String> firstRecordOf(String topic) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                if (!records.isEmpty()) {
                    return records.iterator().next();
                }
            }
        }
        throw new AssertionError("No record arrived on " + topic + " within 30 seconds");
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}