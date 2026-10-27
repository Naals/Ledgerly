package com.project.ledgercore.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Stand-in publisher until a message broker is connected: writes each event to the log. */
@Component
class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(OutboxEvent event) {
        log.info("event {} {} aggregate={} payload={}",
                event.id(), event.eventType(), event.aggregateId(), event.payload());
    }
}