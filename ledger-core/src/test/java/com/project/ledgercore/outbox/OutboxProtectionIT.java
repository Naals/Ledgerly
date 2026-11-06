package com.project.ledgercore.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class OutboxProtectionIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void anEventsPayloadCannotBeEdited() {
        long seq = newEvent();

        assertThatThrownBy(() -> jdbc.update("UPDATE outbox_events SET payload = CAST('{}' AS jsonb) WHERE seq = ?", seq))
                .hasStackTraceContaining("outbox_event_immutable");
    }

    @Test
    void anEventsTypeAndAggregateCannotBeEdited() {
        long seq = newEvent();

        assertThatThrownBy(() -> jdbc.update("UPDATE outbox_events SET event_type = 'X' WHERE seq = ?", seq))
                .hasStackTraceContaining("outbox_event_immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE outbox_events SET aggregate_id = ? WHERE seq = ?", UUID.randomUUID(), seq))
                .hasStackTraceContaining("outbox_event_immutable");
    }

    @Test
    void anUndeliveredEventCannotBeDeleted() {
        long seq = newEvent();

        assertThatThrownBy(() -> jdbc.update("DELETE FROM outbox_events WHERE seq = ?", seq))
                .hasStackTraceContaining("outbox_unpublished_event");
    }

    @Test
    void deliveryBookkeepingMayChangeAndADeliveredEventMayBeDeleted() {
        long seq = newEvent();

        assertThat(jdbc.update("UPDATE outbox_events SET attempts = attempts + 1, last_error = 'x', "
                + "next_attempt_at = now() WHERE seq = ?", seq)).isEqualTo(1);
        assertThat(jdbc.update("UPDATE outbox_events SET published_at = clock_timestamp() WHERE seq = ?", seq)).isEqualTo(1);
        assertThat(jdbc.update("DELETE FROM outbox_events WHERE seq = ?", seq)).isEqualTo(1);
    }

    private long newEvent() {
        return jdbc.queryForObject("INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload) "
                        + "VALUES ('Test', ?, 'TransactionPosted', CAST('{\"a\": 1}' AS jsonb)) RETURNING seq",
                Long.class, UUID.randomUUID());
    }
}