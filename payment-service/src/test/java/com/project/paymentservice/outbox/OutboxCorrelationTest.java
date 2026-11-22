package com.project.paymentservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.project.paymentservice.common.CorrelationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OutboxCorrelationTest {

    @Test
    void aFailedDeliveryAndItsRetryUseTheOriginalContextAndRestoreTheRelayContext() {
        OutboxRepository repository = mock(OutboxRepository.class);
        OutboxEvent event = event("original-request");
        when(repository.lockPending(10)).thenReturn(List.of(event));
        List<String> seen = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        OutboxPublisher publisher = new OutboxPublisher(repository, ignored -> {
            seen.add(CorrelationContext.current());
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("broker down");
            }
        });

        try (CorrelationContext ignored = CorrelationContext.open("relay-context")) {
            assertThat(publisher.publishBatch(10)).isZero();
            assertThat(CorrelationContext.current()).isEqualTo("relay-context");
            assertThat(publisher.publishBatch(10)).isEqualTo(1);
            assertThat(CorrelationContext.current()).isEqualTo("relay-context");
        }
        assertThat(seen).containsExactly("original-request", "original-request");
    }

    @Test
    void aLegacyEventUsesItsIdentityForLogsWithoutInventingStoredMetadata() {
        OutboxRepository repository = mock(OutboxRepository.class);
        OutboxEvent event = event(null);
        when(repository.lockPending(10)).thenReturn(List.of(event));
        OutboxPublisher publisher = new OutboxPublisher(repository, delivered -> {
            assertThat(CorrelationContext.current()).isEqualTo(event.id().toString());
            assertThat(delivered.correlationId()).isNull();
        });

        assertThat(publisher.publishBatch(10)).isEqualTo(1);
        assertThat(CorrelationContext.current()).isNull();
    }

    private static OutboxEvent event(String correlationId) {
        return new OutboxEvent(1, UUID.randomUUID(), "Payment", UUID.randomUUID(),
                "PaymentStatusChanged", "{}", 0, correlationId);
    }
}
