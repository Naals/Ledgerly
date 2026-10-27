package com.project.ledgercore.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** The numbers to alert on: how many events wait for delivery, and how long the oldest one has waited. */
@Component
public class OutboxMetrics {

    public OutboxMetrics(MeterRegistry registry, OutboxRepository outbox) {
        Gauge.builder("ledger.outbox.pending", outbox, OutboxRepository::countPending)
                .description("Outbox events that have not been delivered yet")
                .register(registry);
        Gauge.builder("ledger.outbox.oldest.pending.age", outbox, OutboxRepository::oldestPendingAgeSeconds)
                .description("Age of the oldest outbox event that has not been delivered yet")
                .baseUnit("seconds")
                .register(registry);
    }
}