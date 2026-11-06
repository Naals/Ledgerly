package com.project.paymentservice.events;

import com.project.paymentservice.payment.PaymentStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
class MicrometerEventMetrics implements EventMetrics {

    private final MeterRegistry registry;

    MicrometerEventMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void inconsistent(PaymentStatus paymentStatus) {
        registry.counter("payments.events.inconsistent", "payment_status", paymentStatus.name()).increment();
    }

    @Override
    public void deadLettered(String topic) {
        registry.counter("payments.events.dead.lettered", "topic", topic).increment();
    }

    @Override
    public void reconciled(int authorizations) {
        registry.counter("payments.reconciliation.settled").increment(authorizations);
    }
}