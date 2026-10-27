package com.project.ledgercore.outbox;

/** Delivers outbox events to their destination, for example a message broker. */
public interface EventPublisher {

    /** Delivers the event. Throws if it could not be delivered; the relay will retry it later. */
    void publish(OutboxEvent event);
}