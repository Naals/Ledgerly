package com.project.ledgercore.support;

import com.project.ledgercore.outbox.EventPublisher;
import com.project.ledgercore.outbox.OutboxEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Test double for the broker: records what was delivered and can be told to fail. */
@Component
@Primary
public class RecordingEventPublisher implements EventPublisher {

    private final List<OutboxEvent> published = new CopyOnWriteArrayList<>();
    private volatile Predicate<OutboxEvent> failWhen = event -> false;

    @Override
    public void publish(OutboxEvent event) {
        if (failWhen.test(event)) {
            throw new IllegalStateException("simulated broker outage");
        }
        published.add(event);
    }

    public void failWhen(Predicate<OutboxEvent> condition) {
        this.failWhen = condition;
    }

    public List<OutboxEvent> published() {
        return List.copyOf(published);
    }

    public void reset() {
        published.clear();
        failWhen = event -> false;
    }
}