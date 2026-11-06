package com.project.paymentservice.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.project.paymentservice.ledger.HoldState;
import com.project.paymentservice.support.TestcontainersConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Testcontainers
@SpringBootTest(properties = {
        "payments.retry.enabled=false", "payments.reconciliation.enabled=false",
        "payments.outbox.relay.enabled=false", "payments.outbox.cleanup.enabled=false",
        "payments.kafka.enabled=true", "payments.kafka.ledger-topic=ledger.transaction-posted.v1",
        "payments.kafka.hold-topic=" + PaymentHoldEventsKafkaIT.HOLD_TOPIC})
@Import(TestcontainersConfiguration.class)
class PaymentHoldEventsKafkaIT {

    static final String HOLD_TOPIC = "ledger.hold-status-changed.v1";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    /** Like the ledger's real topic: three partitions. */
    @BeforeAll
    static void createTheHoldTopicWithThreePartitions() throws Exception {
        Properties admin = new Properties();
        admin.put("bootstrap.servers", KAFKA.getBootstrapServers());
        try (AdminClient client = AdminClient.create(admin)) {
            client.createTopics(List.of(new NewTopic(HOLD_TOPIC, 3, (short) 1))).all().get();
        }
    }

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry registry;

    @Test
    void aCapturedHoldFromKafkaCompletesTheAuthorizedPayment() {
        UUID payment = UUID.randomUUID();
        UUID hold = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();
        jdbc.update("INSERT INTO payments (id, idempotency_key, request_hash, from_account_id, to_account_id, "
                        + "amount_minor, currency, capture_mode) VALUES (?, ?, ?, ?, ?, 100, 'KZT', 'MANUAL')",
                payment, "key-" + UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("UPDATE payments SET status = 'AUTHORIZED', ledger_hold_id = ?, "
                + "authorization_expires_at = now() + interval '1 hour' WHERE id = ?", hold, payment);

        send(1, PaymentHoldEventHandlerIT.event(HoldState.CAPTURED, hold, payment.toString(), transaction));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, payment))
                        .isEqualTo("COMPLETED"));
        assertThat(jdbc.queryForObject("SELECT ledger_transaction_id FROM payments WHERE id = ?", UUID.class, payment))
                .isEqualTo(transaction);
    }

    @Test
    void aPoisonHoldEventOnAHigherPartitionIsDeadLetteredAndCounted() {
        send(2, "this is not json");

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(registry.counter("payments.events.dead.lettered", "topic", HOLD_TOPIC).count()).isEqualTo(1));
    }

    private void send(int partition, String payload) {
        ProducerRecord<String, String> record = new ProducerRecord<>(HOLD_TOPIC, partition, UUID.randomUUID().toString(), payload);
        record.headers().add("event-id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        kafka.send(record);
    }
}