package com.project.ledgercore.outbox;

import java.util.LinkedHashMap;
import java.util.Map;

/** What to send to the broker for one outbox event: the topic, the key, the value and the headers. */
public record EventRecordSpec(String topic, String key, String value, Map<String, String> headers) {

    /**
     * The key is the aggregate id, so everything about one transaction lands in one partition. The event id
     * travels as a header so consumers can deduplicate without parsing the payload.
     */
    public static EventRecordSpec of(OutboxEvent event, Map<String, String> topicsByEventType) {
        String topic = topicsByEventType.get(event.eventType());
        if (topic == null) {
            throw new IllegalStateException("No Kafka topic is configured for event type " + event.eventType());
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("event-id", event.id().toString());
        headers.put("event-type", event.eventType());
        headers.put("aggregate-type", event.aggregateType());
        return new EventRecordSpec(topic, event.aggregateId().toString(), event.payload(), Map.copyOf(headers));
    }
}