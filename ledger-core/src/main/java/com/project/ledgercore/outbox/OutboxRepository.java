package com.project.ledgercore.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

    private static final String COLUMNS =
            "seq, id, aggregate_type, aggregate_id, event_type, CAST(payload AS text) AS payload, attempts";

    private static final RowMapper<OutboxEvent> ROW_MAPPER = (rs, rowNum) -> new OutboxEvent(
            rs.getLong("seq"),
            rs.getObject("id", UUID.class),
            rs.getString("aggregate_type"),
            rs.getObject("aggregate_id", UUID.class),
            rs.getString("event_type"),
            rs.getString("payload"),
            rs.getInt("attempts"));

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void add(String aggregateType, UUID aggregateId, String eventType, String payloadJson) {
        jdbc.sql("INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload) "
                        + "VALUES (:aggregateType, :aggregateId, :eventType, CAST(:payload AS jsonb))")
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("payload", payloadJson)
                .update();
    }

    /**
     * Locks up to {@code limit} events that are due, oldest first, for the rest of the transaction.
     * Events another relay has already locked are skipped, not waited for.
     */
    public List<OutboxEvent> lockPending(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM outbox_events "
                        + "WHERE published_at IS NULL AND next_attempt_at <= clock_timestamp() "
                        + "ORDER BY seq LIMIT :limit FOR UPDATE SKIP LOCKED")
                .param("limit", limit)
                .query(ROW_MAPPER)
                .list();
    }

    public void markPublished(long seq) {
        jdbc.sql("UPDATE outbox_events SET published_at = clock_timestamp(), last_error = NULL WHERE seq = :seq")
                .param("seq", seq)
                .update();
    }

    /** Counts the failed attempt and postpones the next one by {@code delaySeconds}. */
    public void markFailed(long seq, long delaySeconds, String error) {
        jdbc.sql("UPDATE outbox_events SET attempts = attempts + 1, "
                        + "next_attempt_at = clock_timestamp() + make_interval(secs => CAST(:delay AS double precision)), "
                        + "last_error = :error WHERE seq = :seq")
                .param("delay", delaySeconds)
                .param("error", error)
                .param("seq", seq)
                .update();
    }

    public long countPending() {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")
                .query((rs, rowNum) -> rs.getLong(1))
                .single();
    }

    /** How long the oldest undelivered event has been waiting; 0 if nothing is waiting. */
    public double oldestPendingAgeSeconds() {
        return jdbc.sql("SELECT COALESCE(EXTRACT(EPOCH FROM (clock_timestamp() - MIN(created_at))), 0) "
                        + "FROM outbox_events WHERE published_at IS NULL")
                .query((rs, rowNum) -> rs.getDouble(1))
                .single();
    }

    public List<OutboxEvent> findByAggregate(UUID aggregateId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM outbox_events WHERE aggregate_id = :aggregateId ORDER BY seq")
                .param("aggregateId", aggregateId)
                .query(ROW_MAPPER)
                .list();
    }
}