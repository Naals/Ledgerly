package com.project.ledgercore.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.support.RecordingEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class OutboxMetricsIT extends AbstractIntegrationTest {

    @Autowired
    MeterRegistry registry;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    OutboxPublisher publisher;

    @Autowired
    RecordingEventPublisher recorder;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void startFromAnEmptyOutbox() {
        jdbc.update("UPDATE outbox_events SET published_at = clock_timestamp() WHERE published_at IS NULL");
        recorder.reset();
    }

    @Test
    void thePendingGaugeFollowsTheNumberOfUndeliveredEvents() {
        for (int i = 0; i < 3; i++) {
            outbox.add("test", UUID.randomUUID(), "TestEvent", "{\"n\": 1}");
        }
        assertThat(pending()).isEqualTo(3.0);

        publisher.publishBatch(10);

        assertThat(pending()).isZero();
    }

    @Test
    void theOldestAgeIsZeroWhenNothingWaitsAndGrowsWhenSomethingDoes() throws Exception {
        assertThat(oldestAge()).isZero();

        outbox.add("test", UUID.randomUUID(), "TestEvent", "{\"n\": 1}");
        Thread.sleep(50);

        assertThat(oldestAge()).isGreaterThan(0.0);
    }

    private double pending() {
        return registry.get("ledger.outbox.pending").gauge().value();
    }

    private double oldestAge() {
        return registry.get("ledger.outbox.oldest.pending.age").gauge().value();
    }
}