package com.project.ledgercore.outbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxPublisher {

    private static final int MAX_ERROR_LENGTH = 500;

    private final OutboxRepository outbox;
    private final EventPublisher publisher;

    public OutboxPublisher(OutboxRepository outbox, EventPublisher publisher) {
        this.outbox = outbox;
        this.publisher = publisher;
    }

    /**
     * Delivers up to {@code batchSize} due events and returns how many were delivered.
     *
     * <p>Events are locked with SKIP LOCKED, so several relays can run side by side without delivering
     * the same event twice. Delivery is at-least-once: if the process dies after the broker accepted an
     * event but before it is marked, the event is delivered again. An event that fails is retried later
     * with a growing delay and does not hold up the others.
     */
    @Transactional
    public int publishBatch(int batchSize) {
        int delivered = 0;
        for (OutboxEvent event : outbox.lockPending(batchSize)) {
            try {
                publisher.publish(event);
                outbox.markPublished(event.seq());
                delivered++;
            } catch (RuntimeException e) {
                outbox.markFailed(event.seq(), Backoff.delaySecondsAfter(event.attempts() + 1), describe(e));
            }
        }
        return delivered;
    }

    private static String describe(RuntimeException e) {
        String text = e.getClass().getSimpleName() + ": " + e.getMessage();
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}