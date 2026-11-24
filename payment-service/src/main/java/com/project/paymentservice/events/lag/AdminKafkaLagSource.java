package com.project.paymentservice.events.lag;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;

/** Samples the configured group independently of listener membership, under one total deadline. */
public final class AdminKafkaLagSource implements KafkaLagSource {

    private final Admin admin;
    private final String groupId;
    private final Set<String> topics;
    private final long timeoutNanos;
    private final LongSupplier nanoTime;

    public AdminKafkaLagSource(Admin admin, String groupId, Set<String> topics, Duration timeout) {
        this(admin, groupId, topics, timeout, System::nanoTime);
    }

    AdminKafkaLagSource(Admin admin, String groupId, Set<String> topics, Duration timeout, LongSupplier nanoTime) {
        this.admin = Objects.requireNonNull(admin, "admin");
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("Consumer group must not be blank");
        }
        this.groupId = groupId;
        this.topics = Set.copyOf(topics);
        if (this.topics.isEmpty() || this.topics.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("At least one nonblank topic is required");
        }
        if (timeout == null || timeout.compareTo(Duration.ofMillis(1)) < 0
                || timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("Timeout must be between 1ms and 2147483647ms");
        }
        this.timeoutNanos = timeout.toNanos();
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public Map<String, TopicLag> read() throws Exception {
        long deadline = nanoTime.getAsLong() + timeoutNanos;
        Map<String, TopicDescription> descriptions = await(admin.describeTopics(topics,
                new DescribeTopicsOptions().timeoutMs(remainingMillis(deadline))).allTopicNames(), deadline);
        List<TopicPartition> partitions = partitions(descriptions);

        KafkaFuture<Map<TopicPartition, OffsetAndMetadata>> committedFuture = admin.listConsumerGroupOffsets(
                Map.of(groupId, new ListConsumerGroupOffsetsSpec().topicPartitions(partitions)),
                new ListConsumerGroupOffsetsOptions().timeoutMs(remainingMillis(deadline)))
                .partitionsToOffsetAndMetadata(groupId);
        KafkaFuture<Map<TopicPartition, ListOffsetsResultInfo>> earliestFuture = offsets(
                partitions, OffsetSpec.earliest(), deadline);
        Map<TopicPartition, OffsetAndMetadata> committed = committed(committedFuture, deadline);
        Map<TopicPartition, ListOffsetsResultInfo> earliest = await(earliestFuture, deadline);
        // Read latest after committed positions to avoid an ordinary commit racing a previous end sample.
        Map<TopicPartition, ListOffsetsResultInfo> latest = await(
                offsets(partitions, OffsetSpec.latest(), deadline), deadline);

        Map<String, List<PartitionLag>> byTopic = new HashMap<>();
        for (TopicPartition partition : partitions) {
            OffsetAndMetadata position = committed.get(partition);
            PartitionLag lag = PartitionLag.between(offset(earliest, partition), offset(latest, partition),
                    position == null ? null : position.offset());
            byTopic.computeIfAbsent(partition.topic(), ignored -> new ArrayList<>()).add(lag);
        }
        Map<String, TopicLag> result = new HashMap<>();
        byTopic.forEach((topic, lag) -> result.put(topic, TopicLag.from(lag)));
        return Map.copyOf(result);
    }

    private List<TopicPartition> partitions(Map<String, TopicDescription> descriptions) {
        List<TopicPartition> partitions = new ArrayList<>();
        for (String topic : topics) {
            TopicDescription description = descriptions.get(topic);
            if (description == null || description.partitions().isEmpty()) {
                throw new IllegalStateException("Missing partition metadata for configured topic " + topic);
            }
            description.partitions().forEach(partition -> partitions.add(new TopicPartition(topic, partition.partition())));
        }
        return partitions;
    }

    private KafkaFuture<Map<TopicPartition, ListOffsetsResultInfo>> offsets(
            List<TopicPartition> partitions, OffsetSpec spec, long deadline) throws TimeoutException {
        Map<TopicPartition, OffsetSpec> request = new HashMap<>();
        partitions.forEach(partition -> request.put(partition, spec));
        return admin.listOffsets(request, new ListOffsetsOptions().timeoutMs(remainingMillis(deadline))).all();
    }

    private Map<TopicPartition, OffsetAndMetadata> committed(
            KafkaFuture<Map<TopicPartition, OffsetAndMetadata>> future, long deadline) throws Exception {
        try {
            return await(future, deadline);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof GroupIdNotFoundException) {
                return Map.of();
            }
            throw failure;
        }
    }

    private static long offset(Map<TopicPartition, ListOffsetsResultInfo> offsets, TopicPartition partition) {
        ListOffsetsResultInfo info = offsets.get(partition);
        if (info == null) {
            throw new IllegalStateException("Missing broker offset for " + partition);
        }
        return info.offset();
    }

    private <T> T await(KafkaFuture<T> future, long deadline) throws Exception {
        try {
            return future.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failure;
        } catch (TimeoutException failure) {
            future.cancel(true);
            throw failure;
        }
    }

    private int remainingMillis(long deadline) throws TimeoutException {
        long nanos = remainingNanos(deadline);
        return (int) Math.min(Integer.MAX_VALUE, 1 + (nanos - 1) / 1_000_000);
    }

    private long remainingNanos(long deadline) throws TimeoutException {
        long remaining = deadline - nanoTime.getAsLong();
        if (remaining <= 0) {
            throw new TimeoutException("Kafka lag sample exceeded its total timeout");
        }
        return remaining;
    }
}
