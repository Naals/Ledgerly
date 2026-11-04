package com.project.paymentservice.support;

import com.project.paymentservice.outbox.EventPublisher;
import com.project.paymentservice.outbox.OutboxEvent;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Test double for the broker: remembers what it was asked to deliver, in order, and fails when a test says so. */
@Component
@Primary
public class RecordingEventPublisher implements EventPublisher {

    private final List<OutboxEvent> published = new CopyOnWriteArrayList<>();
    private volatile Predicate<OutboxEvent> failing = event -> false;
    private volatile long delayMillis = 0;

    @Override
    public void publish(OutboxEvent event) {
        if (failing.test(event)) {
            throw new IllegalStateException("broker down");
        }
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        published.add(event);
    }

    public void failWhen(Predicate<OutboxEvent> condition) {
        this.failing = condition;
    }

    /** Makes every delivery take this long, so that concurrent relays really do overlap. */
    public void slowDownBy(long millis) {
        this.delayMillis = millis;
    }

    public List<OutboxEvent> published() {
        return List.copyOf(published);
    }

    public List<OutboxEvent> publishedFor(UUID aggregateId) {
        return published.stream().filter(event -> event.aggregateId().equals(aggregateId)).toList();
    }

    public void reset() {
        published.clear();
        failing = event -> false;
        delayMillis = 0;
    }
}