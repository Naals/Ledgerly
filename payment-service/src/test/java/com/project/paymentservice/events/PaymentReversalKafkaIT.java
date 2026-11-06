package com.project.paymentservice.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.project.paymentservice.support.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
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
        "payments.retry.enabled=false",
        "payments.kafka.enabled=true",
        "payments.kafka.ledger-topic=" + PaymentReversalKafkaIT.TOPIC})
@Import(TestcontainersConfiguration.class)
class PaymentReversalKafkaIT {

    static final String TOPIC = "ledger.transaction-posted.v1";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aReversalEventFromKafkaReversesThePayment() {
        UUID paymentId = pendingPayment();
        complete(paymentId);

        send(PaymentReversalHandlerIT.reversalOf(paymentId));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(statusOf(paymentId)).isEqualTo("REVERSED"));
    }

    @Test
    void aReversalThatBeatsThePaymentToCompletedIsRetriedUntilThePaymentCatchesUp() {
        UUID paymentId = pendingPayment();

        send(PaymentReversalHandlerIT.reversalOf(paymentId));

        // the ledger is ahead of us: for a while the event keeps failing and the payment is left alone
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(statusOf(paymentId)).isEqualTo("PENDING"));

        complete(paymentId);

        await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                assertThat(statusOf(paymentId)).isEqualTo("REVERSED"));
    }

    private void send(String payload) {
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, UUID.randomUUID().toString(), payload);
        record.headers().add("event-id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        kafka.send(record);
    }

    private UUID pendingPayment() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payments (id, idempotency_key, request_hash, from_account_id, to_account_id, "
                        + "amount_minor, currency) VALUES (?, ?, ?, ?, ?, 100, 'KZT')",
                id, "key-" + UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), UUID.randomUUID());
        return id;
    }

    private void complete(UUID paymentId) {
        jdbc.update("UPDATE payments SET status = 'COMPLETED', ledger_transaction_id = ? WHERE id = ?",
                UUID.randomUUID(), paymentId);
    }

    private String statusOf(UUID paymentId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }

}