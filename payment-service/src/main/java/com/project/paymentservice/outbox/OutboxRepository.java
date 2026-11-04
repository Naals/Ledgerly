package com.project.paymentservice.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

    private static final String COLUMNS = "o.seq, o.id, o.aggregate_type, o.aggregate_id, o.event_type, "
            + "CAST(o.payload AS text) AS payload, o.attempts";

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

    /**
     * Locks up to {@code limit} events that are due, oldest first, for the rest of the transaction. Events another
     * relay has already locked are skipped, not waited for.
     *
     * <p>Only a payment's OLDEST undelivered event is ever handed out: a later event is not eligible while an earlier one
     * of the same payment is still undelivered, whether it is being delivered right now by another relay or is waiting
     * out a retry delay. That is what keeps one payment's events in the order they happened in, however many relays run
     * and whatever fails. Other payments are not affected.
     */
    public List<OutboxEvent> lockPending(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM outbox_events o "
                        + "WHERE o.published_at IS NULL AND o.next_attempt_at <= clock_timestamp() "
                        + "AND NOT EXISTS (SELECT 1 FROM outbox_events earlier "
                        + "WHERE earlier.aggregate_id = o.aggregate_id AND earlier.seq < o.seq "
                        + "AND earlier.published_at IS NULL) "
                        + "ORDER BY o.seq LIMIT :limit FOR UPDATE OF o SKIP LOCKED")
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
        return jdbc.sql("SELECT " + COLUMNS + " FROM outbox_events o WHERE o.aggregate_id = :aggregateId ORDER BY o.seq")
                .param("aggregateId", aggregateId)
                .query(ROW_MAPPER)
                .list();
    }

    /** Deletes up to {@code limit} events that were delivered more than {@code days} days ago. Returns how many. */
    public int deletePublishedOlderThan(int days, int limit) {
        return jdbc.sql("DELETE FROM outbox_events WHERE seq IN ("
                        + "SELECT seq FROM outbox_events "
                        + "WHERE published_at < clock_timestamp() - (:days * interval '1 day') "
                        + "ORDER BY seq LIMIT :limit)")
                .param("days", days)
                .param("limit", limit)
                .update();
    }
}