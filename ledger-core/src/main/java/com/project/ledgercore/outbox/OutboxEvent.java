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
        int attempts) {
}