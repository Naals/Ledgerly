package com.project.ledgercore.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class OutboxCleanupIT extends AbstractIntegrationTest {

    @Autowired
    OutboxRepository outbox;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clearWhatEarlierTestsLeftBehind() {
        outbox.deletePublishedOlderThan(7, 1_000_000);
    }

    @Test
    void onlyEventsDeliveredLongAgoAreDeleted() {
        UUID old = newEvent();
        UUID recent = newEvent();
        UUID undelivered = newEvent();
        jdbc.update("UPDATE outbox_events SET published_at = clock_timestamp() - interval '10 days' WHERE aggregate_id = ?", old);
        jdbc.update("UPDATE outbox_events SET published_at = clock_timestamp() WHERE aggregate_id = ?", recent);

        int deleted = outbox.deletePublishedOlderThan(7, 1_000);

        assertThat(deleted).isEqualTo(1);
        assertThat(outbox.findByAggregate(old)).isEmpty();
        assertThat(outbox.findByAggregate(recent)).hasSize(1);
        assertThat(outbox.findByAggregate(undelivered)).hasSize(1);
    }

    @Test
    void theBatchLimitIsRespected() {
        for (int i = 0; i < 3; i++) {
            jdbc.update("UPDATE outbox_events SET published_at = clock_timestamp() - interval '10 days' WHERE aggregate_id = ?",
                    newEvent());
        }

        assertThat(outbox.deletePublishedOlderThan(7, 2)).isEqualTo(2);
        assertThat(outbox.deletePublishedOlderThan(7, 2)).isEqualTo(1);
    }

    @Test
    void anUndeliveredEventIsNeverDeletedHoweverOldItIs() {
        UUID undelivered = newEvent();

        outbox.deletePublishedOlderThan(0, 1_000);

        assertThat(outbox.findByAggregate(undelivered)).hasSize(1);
    }

    private UUID newEvent() {
        UUID aggregate = UUID.randomUUID();
        jdbc.update("INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload) "
                + "VALUES ('Test', ?, 'TransactionPosted', CAST('{}' AS jsonb))", aggregate);
        return aggregate;
    }
}