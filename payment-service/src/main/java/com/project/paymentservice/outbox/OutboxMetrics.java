package com.project.paymentservice.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** The numbers to alert on: how many events wait for delivery, and how long the oldest one has waited. */
@Component
public class OutboxMetrics {

    public OutboxMetrics(MeterRegistry registry, OutboxRepository outbox) {
        Gauge.builder("payments.outbox.pending", outbox, OutboxRepository::countPending)
                .description("Payment outbox events that have not been delivered yet")
                .register(registry);
        Gauge.builder("payments.outbox.oldest.pending.age", outbox, OutboxRepository::oldestPendingAgeSeconds)
                .description("Age of the oldest payment outbox event that has not been delivered yet")
                .baseUnit("seconds")
                .register(registry);
    }
}