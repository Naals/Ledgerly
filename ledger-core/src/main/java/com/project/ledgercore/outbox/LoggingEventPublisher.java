package com.project.ledgercore.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Default publisher when no broker is configured: writes each event to the log. */
@Component
@ConditionalOnProperty(name = "ledger.outbox.publisher", havingValue = "log", matchIfMissing = true)
class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(OutboxEvent event) {
        log.info("event {} {} aggregate={} payload={}",
                event.id(), event.eventType(), event.aggregateId(), event.payload());
    }
}