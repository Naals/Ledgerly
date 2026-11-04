package com.project.paymentservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.project.paymentservice.support.AbstractIntegrationTest;
import com.project.paymentservice.support.ConcurrentRunner;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class OutboxPublisherIT extends AbstractIntegrationTest {

    private static final List<String> LIFECYCLE = List.of("AUTHORIZED", "COMPLETED", "REVERSED");

    @Autowired
    OutboxPublisher publisher;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void startFromAnEmptyOutbox() {
        jdbc.update("UPDATE outbox_events SET published_at = clock_timestamp() WHERE published_at IS NULL");
    }

    @Test
    void aPaymentsEventsAreDeliveredOneAtATimeInOrder() {
        UUID payment = paymentWithEvents(3);

        assertThat(publisher.publishBatch(100)).isEqualTo(1);   // only the oldest is due: the others wait for it
        assertThat(publisher.publishBatch(100)).isEqualTo(1);
        assertThat(publisher.publishBatch(100)).isEqualTo(1);
        assertThat(publisher.publishBatch(100)).isZero();

        assertThat(statusesPublishedFor(payment)).containsExactlyElementsOf(LIFECYCLE);
    }

    @Test
    void differentPaymentsAreDeliveredTogether() {
        paymentWithEvents(1);
        paymentWithEvents(1);
        paymentWithEvents(1);

        assertThat(publisher.publishBatch(100)).isEqualTo(3);
    }

    @Test
    void aFailingEventHoldsBackItsPaymentsLaterEventsButNobodyElses() {
        UUID stuck = paymentWithEvents(2);
        UUID other = paymentWithEvents(2);
        events.failWhen(event -> event.aggregateId().equals(stuck));

        publisher.publishBatch(100);
        publisher.publishBatch(100);
        publisher.publishBatch(100);

        assertThat(events.publishedFor(stuck)).isEmpty();                    // its second event did not overtake its first
        assertThat(statusesPublishedFor(other)).containsExactly("AUTHORIZED", "COMPLETED");   // the other was not held up
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE aggregate_id = ? ORDER BY seq LIMIT 1",
                Integer.class, stuck)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_events WHERE aggregate_id = ? ORDER BY seq LIMIT 1",
                String.class, stuck)).contains("broker down");

        events.failWhen(event -> false);                                    // the broker recovers ...
        jdbc.update("UPDATE outbox_events SET next_attempt_at = clock_timestamp() WHERE aggregate_id = ?", stuck);
        publisher.publishBatch(100);                                        // ... and the postponement is over
        publisher.publishBatch(100);

        assertThat(statusesPublishedFor(stuck)).containsExactly("AUTHORIZED", "COMPLETED");
    }

    @Test
    void aDeliveredEventIsNotDeliveredAgain() {
        UUID payment = paymentWithEvents(1);

        publisher.publishBatch(100);
        publisher.publishBatch(100);

        assertThat(events.publishedFor(payment)).hasSize(1);
    }

    @Test
    void relaysRunningAtTheSameTimeDeliverEveryEventOnceAndInOrder() throws Exception {
        List<UUID> payments = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            payments.add(paymentWithEvents(3));
        }
        events.slowDownBy(2);
        List<Callable<Integer>> relays = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            relays.add(() -> {
                int delivered = 0;
                int batch;
                while ((batch = publisher.publishBatch(10)) > 0) {
                    delivered += batch;
                }
                return delivered;
            });
        }

        ConcurrentRunner.runAll(4, relays);
        while (publisher.publishBatch(100) > 0) {
            // whatever the four runs left behind
        }

        for (UUID payment : payments) {
            assertThat(statusesPublishedFor(payment)).as("payment " + payment).containsExactlyElementsOf(LIFECYCLE);
        }
        assertThat(events.published().stream().map(OutboxEvent::seq).distinct().count())
                .isEqualTo(events.published().size());   // nothing was delivered twice
    }

    /** A payment that has gone through the first {@code count} steps of AUTHORIZED, COMPLETED, REVERSED. */
    private UUID paymentWithEvents(int count) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payments (id, idempotency_key, request_hash, from_account_id, to_account_id, "
                        + "amount_minor, currency, capture_mode) VALUES (?, ?, ?, ?, ?, 100, 'KZT', 'MANUAL')",
                id, "key-" + UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("UPDATE payments SET status = 'AUTHORIZED', ledger_hold_id = ?, "
                + "authorization_expires_at = now() + interval '1 hour' WHERE id = ?", UUID.randomUUID(), id);
        if (count >= 2) {
            jdbc.update("UPDATE payments SET status = 'COMPLETED', ledger_transaction_id = ? WHERE id = ?",
                    UUID.randomUUID(), id);
        }
        if (count >= 3) {
            jdbc.update("UPDATE payments SET status = 'REVERSED' WHERE id = ?", id);
        }
        return id;
    }

    private List<String> statusesPublishedFor(UUID payment) {
        return events.publishedFor(payment).stream()
                .map(event -> JsonPath.<String>read(event.payload(), "$.status"))
                .toList();
    }
}