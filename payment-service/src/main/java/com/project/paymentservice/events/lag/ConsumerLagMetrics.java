package com.project.paymentservice.events.lag;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.ToDoubleFunction;

/** Scrapes read one cached sample; they never contact Kafka. */
public final class ConsumerLagMetrics {
    private final Set<String> topics;
    private final Duration maxStaleness;
    private final LongSupplier nanoTime;
    private final AtomicReference<Snapshot> snapshot;
    private final Counter failures;

    public ConsumerLagMetrics(MeterRegistry registry, String group, Set<String> topics, Duration maxStaleness) {
        this(registry, group, topics, maxStaleness, System::nanoTime);
    }

    ConsumerLagMetrics(MeterRegistry registry, String group, Set<String> topics,
                       Duration maxStaleness, LongSupplier nanoTime) {
        this.topics = Set.copyOf(topics);
        this.maxStaleness = maxStaleness;
        this.nanoTime = nanoTime;
        this.snapshot = new AtomicReference<>(new Snapshot(Map.of(), nanoTime.getAsLong(), false));
        for (String topic : this.topics) {
            topicGauge(registry, group, topic, "payments.kafka.consumer.lag", TopicLag::lag);
            topicGauge(registry, group, topic, "payments.kafka.consumer.lag.max", TopicLag::maxLag);
            topicGauge(registry, group, topic, "payments.kafka.consumer.uncommitted.partitions",
                    TopicLag::uncommittedPartitions);
            topicGauge(registry, group, topic, "payments.kafka.consumer.invalid.offsets", TopicLag::invalidOffsets);
        }
        Gauge.builder("payments.kafka.lag.sample.success", this,
                        metrics -> metrics.isFresh(metrics.snapshot.get()) ? 1 : 0)
                .tag("group", group).register(registry);
        Gauge.builder("payments.kafka.lag.sample.age", this,
                        metrics -> metrics.ageNanos(metrics.snapshot.get()) / 1_000_000_000.0)
                .baseUnit("seconds").tag("group", group).register(registry);
        failures = Counter.builder("payments.kafka.lag.sample.failures").tag("group", group).register(registry);
    }

    public void recordSuccess(Map<String, TopicLag> values) {
        Map<String, TopicLag> complete = Map.copyOf(values);
        if (!complete.keySet().equals(topics)) {
            throw new IllegalArgumentException("Lag sample must include exactly the configured topics");
        }
        snapshot.set(new Snapshot(complete, nanoTime.getAsLong(), true));
    }

    public void recordFailure() {
        snapshot.updateAndGet(previous -> new Snapshot(Map.of(), previous.lastSuccessNanos(), false));
        failures.increment();
    }

    private void topicGauge(MeterRegistry registry, String group, String topic, String name,
                            ToDoubleFunction<TopicLag> value) {
        Gauge.builder(name, this, metrics -> {
            Snapshot current = metrics.snapshot.get();
            return metrics.isFresh(current) ? value.applyAsDouble(current.values().get(topic)) : Double.NaN;
        }).tags("group", group, "topic", topic).register(registry);
    }

    private boolean isFresh(Snapshot current) {
        return current.success() && Duration.ofNanos(ageNanos(current)).compareTo(maxStaleness) < 0;
    }

    private long ageNanos(Snapshot current) {
        return Math.max(0, nanoTime.getAsLong() - current.lastSuccessNanos());
    }

    private record Snapshot(Map<String, TopicLag> values, long lastSuccessNanos, boolean success) {}
}
