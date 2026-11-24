package com.project.paymentservice.events.lag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.errors.GroupAuthorizationException;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AdminKafkaLagSourceTest {

    private static final TopicPartition LEDGER_0 = new TopicPartition("ledger", 0);
    private static final TopicPartition LEDGER_1 = new TopicPartition("ledger", 1);
    private static final TopicPartition HOLD_0 = new TopicPartition("hold", 0);
    private final Admin admin = mock(Admin.class);
    private final DescribeTopicsResult descriptions = mock(DescribeTopicsResult.class);
    private final ListConsumerGroupOffsetsResult committed = mock(ListConsumerGroupOffsetsResult.class);
    private final ListOffsetsResult earliest = mock(ListOffsetsResult.class);
    private final ListOffsetsResult latest = mock(ListOffsetsResult.class);
    private final AtomicLong nanoTime = new AtomicLong();
    private final Map<String, TopicDescription> topicMetadata = Map.of("ledger", topic("ledger", 2), "hold", topic("hold", 1));
    private final Map<TopicPartition, ListOffsetsResultInfo> starts = Map.of(LEDGER_0, offset(0), LEDGER_1, offset(4), HOLD_0, offset(0));
    private final Map<TopicPartition, ListOffsetsResultInfo> ends = Map.of(LEDGER_0, offset(10), LEDGER_1, offset(8), HOLD_0, offset(2));

    @BeforeEach
    void setUp() {
        when(admin.describeTopics(anyCollection(), any(DescribeTopicsOptions.class))).thenReturn(descriptions);
        when(descriptions.allTopicNames()).thenReturn(KafkaFuture.completedFuture(topicMetadata));
        when(admin.listConsumerGroupOffsets(anyMap(), any(ListConsumerGroupOffsetsOptions.class))).thenReturn(committed);
        when(committed.partitionsToOffsetAndMetadata("payments")).thenReturn(KafkaFuture.completedFuture(Map.of()));
        when(admin.listOffsets(anyMap(), any(ListOffsetsOptions.class))).thenAnswer(invocation -> {
            Map<TopicPartition, OffsetSpec> request = invocation.getArgument(0);
            boolean isEarliest = request.values().iterator().next().getClass().equals(OffsetSpec.earliest().getClass());
            return isEarliest ? earliest : latest;
        });
        when(earliest.all()).thenReturn(KafkaFuture.completedFuture(starts));
        when(latest.all()).thenReturn(KafkaFuture.completedFuture(ends));
    }

    @Test
    void discoversUncommittedPartitionsAndIgnoresOffsetsForOtherTopics() throws Exception {
        Map<TopicPartition, OffsetAndMetadata> positions = new HashMap<>();
        positions.put(LEDGER_0, new OffsetAndMetadata(7));
        positions.put(LEDGER_1, null); // Kafka explicitly returns null for some missing commits.
        positions.put(new TopicPartition("unrelated", 0), new OffsetAndMetadata(99));
        when(committed.partitionsToOffsetAndMetadata("payments")).thenReturn(KafkaFuture.completedFuture(positions));

        assertThat(source().read()).containsOnly(
                Map.entry("ledger", new TopicLag(7, 4, 1, 0)), Map.entry("hold", new TopicLag(2, 2, 1, 0)));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ListConsumerGroupOffsetsSpec>> request = ArgumentCaptor.forClass(Map.class);
        verify(admin).listConsumerGroupOffsets(request.capture(), any(ListConsumerGroupOffsetsOptions.class));
        assertThat(request.getValue()).containsOnlyKeys("payments");
        assertThat(request.getValue().get("payments").topicPartitions()).containsExactlyInAnyOrder(LEDGER_0, LEDGER_1, HOLD_0);
    }

    @Test
    void aGroupThatHasNeverCommittedStillReportsRetainedBacklog() throws Exception {
        when(committed.partitionsToOffsetAndMetadata("payments")).thenReturn(failed(new GroupIdNotFoundException("absent")));
        assertThat(source().read().get("ledger")).isEqualTo(new TopicLag(14, 10, 2, 0));
    }

    @Test
    void authorizationFailuresDoNotMasqueradeAsUncommittedOffsets() {
        when(committed.partitionsToOffsetAndMetadata("payments"))
                .thenReturn(failed(new GroupAuthorizationException("denied")));
        assertThatThrownBy(() -> source().read()).hasCauseInstanceOf(GroupAuthorizationException.class);
        verify(admin, times(1)).listOffsets(anyMap(), any(ListOffsetsOptions.class));
    }

    @Test
    void aTruncatedCommittedOffsetMakesOnlyThatTopicsLagUnknown() throws Exception {
        when(committed.partitionsToOffsetAndMetadata("payments"))
                .thenReturn(KafkaFuture.completedFuture(Map.of(LEDGER_1, new OffsetAndMetadata(3))));
        Map<String, TopicLag> result = source().read();
        assertThat(result.get("ledger")).isEqualTo(new TopicLag(Double.NaN, Double.NaN, 1, 1));
        assertThat(result.get("hold")).isEqualTo(new TopicLag(2, 2, 1, 0));
    }

    @Test
    void incompleteTopicMetadataAndOffsetResponsesFailTheSample() {
        when(descriptions.allTopicNames()).thenReturn(KafkaFuture.completedFuture(Map.of("ledger", topic("ledger", 2))));
        assertThatThrownBy(() -> source().read()).isInstanceOf(IllegalStateException.class).hasMessageContaining("hold");
        when(descriptions.allTopicNames()).thenReturn(KafkaFuture.completedFuture(topicMetadata));
        when(latest.all()).thenReturn(KafkaFuture.completedFuture(Map.of()));
        assertThatThrownBy(() -> source().read()).isInstanceOf(IllegalStateException.class).hasMessageContaining("Missing broker offset");
    }

    @Test
    void callsShareOneMonotonicDeadlineAndPassTheRemainingBudgetToKafka() throws Exception {
        @SuppressWarnings("unchecked")
        KafkaFuture<Map<String, TopicDescription>> delayed = mock(KafkaFuture.class);
        when(delayed.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenAnswer(invocation -> {
            nanoTime.set(TimeUnit.MILLISECONDS.toNanos(90));
            return topicMetadata;
        });
        when(descriptions.allTopicNames()).thenReturn(delayed);
        source().read();

        ArgumentCaptor<ListConsumerGroupOffsetsOptions> groupOptions = ArgumentCaptor.forClass(ListConsumerGroupOffsetsOptions.class);
        verify(admin).listConsumerGroupOffsets(anyMap(), groupOptions.capture());
        assertThat(groupOptions.getValue().timeoutMs()).isEqualTo(10);
        ArgumentCaptor<ListOffsetsOptions> offsetOptions = ArgumentCaptor.forClass(ListOffsetsOptions.class);
        verify(admin, times(2)).listOffsets(anyMap(), offsetOptions.capture());
        assertThat(offsetOptions.getAllValues()).extracting(ListOffsetsOptions::timeoutMs).containsExactly(10, 10);
    }

    @Test
    void noFurtherBrokerCallStartsAfterTheTotalDeadline() throws Exception {
        @SuppressWarnings("unchecked")
        KafkaFuture<Map<TopicPartition, ListOffsetsResultInfo>> delayed = mock(KafkaFuture.class);
        when(delayed.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenAnswer(invocation -> {
            nanoTime.set(TimeUnit.MILLISECONDS.toNanos(101));
            return starts;
        });
        when(earliest.all()).thenReturn(delayed);
        assertThatThrownBy(() -> source().read()).isInstanceOf(TimeoutException.class);
        verify(admin, times(1)).listOffsets(anyMap(), any(ListOffsetsOptions.class));
    }

    @Test
    void aFutureThatNeverCompletesIsCancelledWithinTheSampleBudget() {
        KafkaFutureImpl<Map<String, TopicDescription>> pending = new KafkaFutureImpl<>();
        when(descriptions.allTopicNames()).thenReturn(pending);
        AdminKafkaLagSource source = new AdminKafkaLagSource(admin, "payments", Set.of("ledger", "hold"), Duration.ofMillis(20));
        assertThatThrownBy(source::read).isInstanceOf(TimeoutException.class);
        assertThat(pending.isCancelled()).isTrue();
    }

    @Test
    void interruptionIsPreservedForSchedulerShutdown() {
        when(descriptions.allTopicNames()).thenReturn(new KafkaFutureImpl<>());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> source().read()).isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private AdminKafkaLagSource source() {
        return new AdminKafkaLagSource(admin, "payments", Set.of("ledger", "hold"), Duration.ofMillis(100), nanoTime::get);
    }

    private static TopicDescription topic(String name, int partitions) {
        List<TopicPartitionInfo> info = java.util.stream.IntStream.range(0, partitions)
                .mapToObj(partition -> new TopicPartitionInfo(partition, Node.noNode(), List.of(), List.of())).toList();
        return new TopicDescription(name, false, info);
    }

    private static ListOffsetsResultInfo offset(long value) {
        return new ListOffsetsResultInfo(value, -1, Optional.empty());
    }

    private static <T> KafkaFuture<T> failed(Throwable failure) {
        KafkaFutureImpl<T> future = new KafkaFutureImpl<>();
        future.completeExceptionally(failure);
        return future;
    }
}
