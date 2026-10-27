package com.project.ledgercore.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.ledgercore.support.AbstractIntegrationTest;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    OutboxRepository outbox;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    ObjectMapper json;

    @BeforeEach
    void startFromAnEmptyOutbox() {
        jdbc.update("UPDATE outbox_events SET published_at = clock_timestamp() WHERE published_at IS NULL");
    }

    @Test
    void anAddedEventIsStoredAsPendingJson() throws Exception {
        OutboxEvent event = newEvent();

        assertThat(event.attempts()).isZero();
        assertThat(event.aggregateType()).isEqualTo("test");
        assertThat(json.readTree(event.payload()).get("n").asInt()).isEqualTo(1);
        assertThat(outbox.countPending()).isEqualTo(1);
    }

    @Test
    void lockPendingReturnsTheOldestFirst() {
        OutboxEvent first = newEvent();
        OutboxEvent second = newEvent();
        newEvent();

        List<OutboxEvent> batch = tx.execute(status -> outbox.lockPending(2));

        assertThat(batch).extracting(OutboxEvent::seq).containsExactly(first.seq(), second.seq());
    }

    @Test
    void aPublishedEventLeavesThePendingSet() {
        OutboxEvent event = newEvent();

        outbox.markPublished(event.seq());

        assertThat(outbox.countPending()).isZero();
        assertThat(Collections.singletonList(tx.execute(status -> outbox.lockPending(10)))).isEmpty();
    }

    @Test
    void aFailedEventWaitsBeforeItIsDueAgain() {
        OutboxEvent event = newEvent();

        outbox.markFailed(event.seq(), 60, "broker down");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT attempts, last_error FROM outbox_events WHERE seq = ?", event.seq());
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("last_error")).isEqualTo("broker down");
        assertThat(Collections.singletonList(tx.execute(status -> outbox.lockPending(10)))).isEmpty();   // not due for another minute
        assertThat(outbox.countPending()).isEqualTo(1);                       // but still waiting to be delivered
    }

    private OutboxEvent newEvent() {
        UUID aggregateId = UUID.randomUUID();
        outbox.add("test", aggregateId, "TestEvent", "{\"n\": 1}");
        return outbox.findByAggregate(aggregateId).get(0);
    }
}