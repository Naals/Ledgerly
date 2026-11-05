package com.project.paymentservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.project.paymentservice.payment.CaptureMode;
import com.project.paymentservice.payment.CreatePayment;
import com.project.paymentservice.payment.Payment;
import com.project.paymentservice.payment.PaymentService;
import com.project.paymentservice.support.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
@SpringBootTest(properties = {
        "payments.retry.enabled=false", "payments.reconciliation.enabled=false", "payments.kafka.enabled=false",
        "payments.outbox.relay.enabled=false", "payments.outbox.cleanup.enabled=false",
        "payments.outbox.publisher=kafka",
        "payments.outbox.kafka.topics.PaymentStatusChanged=" + PaymentEventsKafkaIT.TOPIC})
@Import(TestcontainersConfiguration.class)
class PaymentEventsKafkaIT {

    static final String TOPIC = "payments.payment-status-changed.v1";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    PaymentService payments;

    @Autowired
    OutboxPublisher outbox;

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    @Test
    void aPaymentsEventsArriveInOrderUnderOneKeyWithTheirIdentityInHeaders() {
        Payment authorized = payments.create("key-" + UUID.randomUUID(), new CreatePayment(
                UUID.randomUUID(), UUID.randomUUID(), 100_000, "KZT", CaptureMode.MANUAL)).payment();
        payments.capture(authorized.id());
        while (outbox.publishBatch(100) > 0) {
            // a payment's next event is only due once its previous one is delivered
        }

        List<ConsumerRecord<String, String>> records = recordsFor(authorized.id().toString(), 2);

        assertThat(records).extracting(record -> JsonPath.<String>read(record.value(), "$.status"))
                .containsExactly("AUTHORIZED", "COMPLETED");
        assertThat(records).extracting(record -> JsonPath.<Integer>read(record.value(), "$.sequence"))
                .containsExactly(1, 2);
        assertThat(records.get(1).partition()).isEqualTo(records.get(0).partition());
        assertThat(header(records.get(0), "event-type")).isEqualTo("PaymentStatusChanged");
        assertThat(header(records.get(0), "event-id")).isNotEqualTo(header(records.get(1), "event-id"));
    }

    private List<ConsumerRecord<String, String>> recordsFor(String key, int expected) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(TOPIC));
            Instant giveUp = Instant.now().plusSeconds(30);
            while (found.size() < expected && Instant.now().isBefore(giveUp)) {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> {
                    if (key.equals(record.key())) {
                        found.add(record);
                    }
                });
            }
        }
        return found;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}