package com.project.paymentservice.events;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProcessedEventRepository {

    private final JdbcClient jdbc;

    public ProcessedEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Remembers that the consumer handled the event. True if this is the first time; false if it was handled
     * before. If another delivery of the same event is being handled right now, this waits for it.
     */
    public boolean markProcessed(String consumer, UUID eventId) {
        return jdbc.sql("INSERT INTO processed_events (consumer, event_id) VALUES (:consumer, :eventId) "
                        + "ON CONFLICT DO NOTHING")
                .param("consumer", consumer)
                .param("eventId", eventId)
                .update() == 1;
    }
}