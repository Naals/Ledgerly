package com.project.paymentservice.events;

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
        if (header == null) {
            throw new IllegalArgumentException(
                    "Event without an event-id header at " + record.topic() + "-" + record.partition() + "@" + record.offset());
        }
        return UUID.fromString(new String(header.value(), StandardCharsets.UTF_8));
    }
}