package com.project.ledgercore.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes events into the outbox, always inside the transaction of the change that caused them. */
@Service
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final ObjectMapper json;

    public OutboxWriter(OutboxRepository outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    /** Refuses to run outside a transaction: an event written on its own could outlive a rolled-back change. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        try {
            outbox.add(aggregateType, aggregateId, eventType, json.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise outbox event " + eventType, e);
        }
    }
}