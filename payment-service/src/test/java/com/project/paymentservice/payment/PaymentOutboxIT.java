package com.project.paymentservice.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.paymentservice.ledger.LedgerOutcome;
import com.project.paymentservice.ledger.LedgerResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PaymentOutboxIT extends AbstractTwoStepIT {

    @Autowired
    ObjectMapper json;

    @Autowired
    PaymentRepository repository;

    @Test
    void aTwoStepPaymentLeavesAnAuthorizedThenACompletedEvent() {
        Payment authorized = payments.create(newKey(), manual()).payment();
        UUID transaction = UUID.randomUUID();
        ledger.captureWith(payment -> new LedgerOutcome.Done<>(transaction));
        payments.capture(authorized.id());

        List<JsonNode> events = eventsOf(authorized.id());

        assertThat(events).extracting(event -> event.get("status").asText()).containsExactly("AUTHORIZED", "COMPLETED");
        assertThat(events).extracting(event -> event.get("sequence").asInt()).containsExactly(1, 2);
        assertThat(events.get(0).get("authorizationExpiresAt").asText()).isNotEmpty();
        assertThat(events.get(1).get("ledgerTransactionId").asText()).isEqualTo(transaction.toString());
    }

    @Test
    void aOneStepPaymentLeavesOneCompletedEvent() {
        Payment payment = payments.create(newKey(),
                new CreatePayment(UUID.randomUUID(), UUID.randomUUID(), 5_000, "KZT")).payment();

        List<JsonNode> events = eventsOf(payment.id());

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("status").asText()).isEqualTo("COMPLETED");
        assertThat(events.getFirst().get("previousStatus").asText()).isEqualTo("PENDING");
        assertThat(events.getFirst().get("captureMode").asText()).isEqualTo("AUTOMATIC");
    }

    @Test
    void aRefusedPaymentLeavesAFailedEventWithTheLedgersCode() {
        ledger.respondWith(payment -> new LedgerResult.Rejected("insufficient-funds"));

        Payment payment = payments.create(newKey(),
                new CreatePayment(UUID.randomUUID(), UUID.randomUUID(), 5_000, "KZT")).payment();

        List<JsonNode> events = eventsOf(payment.id());
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("status").asText()).isEqualTo("FAILED");
        assertThat(events.getFirst().get("failureCode").asText()).isEqualTo("insufficient-funds");
    }

    @Test
    void aCancelledAuthorizationLeavesACancelledEvent() {
        Payment authorized = authorized();
        payments.cancel(authorized.id());

        assertThat(eventsOf(authorized.id())).extracting(event -> event.get("status").asText())
                .containsExactly("AUTHORIZED", "CANCELLED");
    }

    @Test
    void anExpiredAuthorizationLeavesAnExpiredEvent() {
        Payment authorized = authorized();
        ledger.captureWith(payment -> new LedgerOutcome.Rejected<>("hold-expired"));
        try {
            payments.capture(authorized.id());
        } catch (PaymentNotCapturableException expected) {
            // the payment became EXPIRED; that is what this test is about
        }

        assertThat(eventsOf(authorized.id())).extracting(event -> event.get("status").asText())
                .containsExactly("AUTHORIZED", "EXPIRED");
    }

    @Test
    void aReversedPaymentLeavesAReversedEvent() {
        Payment payment = payments.create(newKey(),
                new CreatePayment(UUID.randomUUID(), UUID.randomUUID(), 5_000, "KZT")).payment();
        repository.markReversed(payment.id());

        assertThat(eventsOf(payment.id())).extracting(event -> event.get("status").asText())
                .containsExactly("COMPLETED", "REVERSED");
    }

    @Test
    void anUnavailableLedgerLeavesNoEventUntilTheOutcomeIsKnown() {
        ledger.authorizeWith(payment -> new LedgerOutcome.Unavailable<>("down"));
        Payment pending = payments.create(newKey(), manual()).payment();
        assertThat(eventsOf(pending.id())).isEmpty();   // counting an attempt is not a status change

        ledger.reset();
        payments.execute(pending.id());

        assertThat(eventsOf(pending.id())).extracting(event -> event.get("status").asText())
                .containsExactly("AUTHORIZED");
    }

    private List<JsonNode> eventsOf(UUID paymentId) {
        return jdbc.queryForList("SELECT CAST(payload AS text) FROM outbox_events WHERE aggregate_id = ? ORDER BY seq",
                String.class, paymentId).stream().map(this::parse).toList();
    }

    private JsonNode parse(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}