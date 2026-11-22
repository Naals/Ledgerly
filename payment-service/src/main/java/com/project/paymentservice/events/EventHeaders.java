package com.project.paymentservice.events;

import com.project.paymentservice.common.CorrelationContext;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

final class EventHeaders {

    private EventHeaders() {
    }

    /** The event's identity, from the {@code event-id} header the ledger's relay sets. */
    static UUID eventId(ConsumerRecord<?, ?> record) {
        Header header = record.headers().lastHeader("event-id");
        if (header == null || header.value() == null) {
            throw new IllegalArgumentException(
                    "Event without an event-id header at " + record.topic() + "-" + record.partition() + "@" + record.offset());
        }
        return UUID.fromString(new String(header.value(), StandardCharsets.UTF_8));
    }

    /** Unsafe, absent or oversized transport metadata falls back to the event's stable identity. */
    static String correlationId(ConsumerRecord<?, ?> record, UUID eventId) {
        Header header = record.headers().lastHeader(CorrelationContext.KAFKA_HEADER);
        if (header != null && header.value() != null && header.value().length <= 64) {
            String candidate = new String(header.value(), StandardCharsets.UTF_8);
            if (CorrelationContext.isValid(candidate)) {
                return candidate;
            }
        }
        return eventId.toString();
    }

    /** Dead letters must remain observable even when their event identity was malformed. */
    static String deadLetterCorrelationId(ConsumerRecord<?, ?> record) {
        UUID identity;
        try {
            identity = eventId(record);
        } catch (IllegalArgumentException exception) {
            identity = UUID.nameUUIDFromBytes((record.topic() + ":" + record.partition() + ":" + record.offset())
                    .getBytes(StandardCharsets.UTF_8));
        }
        return correlationId(record, identity);
    }
}
