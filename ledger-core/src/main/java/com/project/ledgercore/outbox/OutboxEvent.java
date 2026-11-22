package com.project.ledgercore.outbox;

import java.util.UUID;

/** An event waiting in (or taken from) the outbox. {@code id} is the stable identity consumers can deduplicate on. */
public record OutboxEvent(
        long seq,
        UUID id,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String payload,
        int attempts,
        String correlationId) {

    /** Existing events and callers may have no request correlation metadata. */
    public OutboxEvent(long seq, UUID id, String aggregateType, UUID aggregateId,
                       String eventType, String payload, int attempts) {
        this(seq, id, aggregateType, aggregateId, eventType, payload, attempts, null);
    }
}