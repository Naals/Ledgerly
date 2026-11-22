package com.project.ledgercore.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.project.ledgercore.common.CorrelationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxPublisherCorrelationTest {

    @Test
    void eachDeliveryUsesPersistedContextAndRestoresTheWorkerEvenWhenDeliveryFails() {
        OutboxEvent correlated = new OutboxEvent(1, UUID.randomUUID(), "test", UUID.randomUUID(), "TestEvent", "{}", 0,
                "original-request");
        OutboxEvent legacy = new OutboxEvent(2, UUID.randomUUID(), "test", UUID.randomUUID(), "TestEvent", "{}", 0);
        OutboxRepository repository = mock(OutboxRepository.class);
        when(repository.lockPending(anyInt())).thenReturn(List.of(correlated, legacy));
        List<String> seen = new ArrayList<>();
        OutboxPublisher publisher = new OutboxPublisher(repository, event -> {
            seen.add(CorrelationContext.current());
            if (event.seq() == 1) {
                throw new IllegalStateException("broker unavailable");
            }
        });

        try (CorrelationContext ignored = CorrelationContext.open("worker-parent")) {
            assertThat(publisher.publishBatch(10)).isEqualTo(1);
            assertThat(CorrelationContext.current()).isEqualTo("worker-parent");
        }
        assertThat(seen).containsExactly("original-request", legacy.id().toString());
        verify(repository).markFailed(anyLong(), anyLong(), anyString());
        verify(repository).markPublished(legacy.seq());
    }

    @Test
    void retriesKeepTheStoredHeaderDespiteAnotherWorkersContext() {
        UUID id = UUID.randomUUID();
        OutboxEvent first = new OutboxEvent(1, id, "test", UUID.randomUUID(), "TestEvent", "{}", 0, "request-1");
        OutboxEvent retry = new OutboxEvent(first.seq(), id, first.aggregateType(), first.aggregateId(), first.eventType(),
                first.payload(), 3, first.correlationId());
        Map<String, String> topics = Map.of("TestEvent", "test.events");
        try (CorrelationContext ignored = CorrelationContext.open("different-worker")) {
            assertThat(EventRecordSpec.of(retry, topics).headers()).isEqualTo(EventRecordSpec.of(first, topics).headers());
            assertThat(EventRecordSpec.of(retry, topics).headers()).containsEntry("correlation-id", "request-1");
        }
    }
}
