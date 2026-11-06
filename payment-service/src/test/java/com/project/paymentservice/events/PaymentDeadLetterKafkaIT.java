package com.project.paymentservice.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.paymentservice.support.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(properties = {
        "payments.retry.enabled=false", "payments.reconciliation.enabled=false",
        "payments.outbox.relay.enabled=false", "payments.outbox.cleanup.enabled=false",
        "payments.kafka.enabled=true", "payments.kafka.ledger-topic=" + PaymentDeadLetterKafkaIT.TOPIC})
@Import(TestcontainersConfiguration.class)
class PaymentDeadLetterKafkaIT {

    static final String TOPIC = "ledger.transaction-posted.v1";

    @Autowired
    MeterRegistry registry;

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    /** Like the ledger's real topic: three partitions, not the one an auto-created topic would have. */
    @BeforeAll
    static void createTheSourceTopicWithThreePartitions() throws Exception {
        Properties admin = new Properties();
        admin.put("bootstrap.servers", KAFKA.getBootstrapServers());
        try (AdminClient client = AdminClient.create(admin)) {
            client.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get();
        }
    }

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Test
    void aPoisonEventOnAHigherPartitionIsMovedToTheDeadLetterTopic() {
        String poison = "this is not json";
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, 2, UUID.randomUUID().toString(), poison);
        record.headers().add("event-id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        kafka.send(record);

        ConsumerRecord<String, String> deadLetter = firstRecordOf(TOPIC + ".DLT");

        assertThat(deadLetter.value()).isEqualTo(poison);
    }

    private ConsumerRecord<String, String> firstRecordOf(String topic) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(topic));
            Instant giveUp = Instant.now().plus(Duration.ofSeconds(60));
            while (Instant.now().isBefore(giveUp)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    return record;
                }
            }
        }
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(registry.counter("payments.events.dead.lettered", "topic", TOPIC).count()).isEqualTo(1));
        throw new AssertionError("Nothing arrived on " + topic + " within 60 seconds");
    }

}