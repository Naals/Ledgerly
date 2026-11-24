package com.project.paymentservice.events.lag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
class KafkaLagSourceIT {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @Test
    void followsBacklogDrainStoppedConsumersAndNewPartitionsAgainstKafka() throws Exception {
        String topic = "lag-" + UUID.randomUUID();
        String group = "lag-group-" + UUID.randomUUID();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
                KafkaProducer<String, String> producer = producer()) {
            admin.createTopics(List.of(new NewTopic(topic, 2, (short) 1))).all().get(15, TimeUnit.SECONDS);
            AdminKafkaLagSource source = new AdminKafkaLagSource(admin, group, Set.of(topic), Duration.ofSeconds(5));
            send(producer, topic, 0, 3);
            send(producer, topic, 1, 2);

            // No group/commits exist yet; all retained records must still contribute to lag.
            assertLag(source, topic, new TopicLag(5, 3, 2, 0));
            try (KafkaConsumer<String, String> consumer = consumer(group)) {
                consumer.subscribe(List.of(topic));
                int received = 0;
                long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                while (received < 5 && System.nanoTime() < deadline) {
                    received += consumer.poll(Duration.ofMillis(250)).count();
                }
                assertThat(received).isEqualTo(5);
                consumer.commitSync(Duration.ofSeconds(5));
                assertLag(source, topic, new TopicLag(0, 0, 0, 0));
            }

            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(admin.describeConsumerGroups(List.of(group)).describedGroups().get(group)
                            .get(5, TimeUnit.SECONDS).members()).isEmpty());
            send(producer, topic, 0, 3);
            assertLag(source, topic, new TopicLag(3, 3, 0, 0));

            // Metadata is refreshed each sample; an added partition has no committed group offset.
            admin.createPartitions(Map.of(topic, NewPartitions.increaseTo(3))).all().get(15, TimeUnit.SECONDS);
            send(producer, topic, 2, 4);
            assertLag(source, topic, new TopicLag(7, 4, 1, 0));
        }
    }

    private static void assertLag(KafkaLagSource source, String topic, TopicLag expected) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(source.read().get(topic)).isEqualTo(expected));
    }

    private static void send(KafkaProducer<String, String> producer, String topic, int partition, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            producer.send(new ProducerRecord<>(topic, partition, "key", "value")).get(15, TimeUnit.SECONDS);
        }
    }

    private static KafkaProducer<String, String> producer() {
        return new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000));
    }

    private static KafkaConsumer<String, String> consumer(String group) {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
    }
}
