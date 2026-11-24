package com.project.paymentservice.events.lag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ConsumerLagMetricsTest {
    private static final Set<String> TOPICS = Set.of("ledger", "holds");
    private static final String[] TOPIC_METRICS = {
            "payments.kafka.consumer.lag", "payments.kafka.consumer.lag.max",
            "payments.kafka.consumer.uncommitted.partitions", "payments.kafka.consumer.invalid.offsets"
    };
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicLong now = new AtomicLong();
    private final ConsumerLagMetrics metrics = new ConsumerLagMetrics(
            registry, "payments", TOPICS, Duration.ofSeconds(60), now::get);

    @Test
    void startupIsUnknownAndScrapesNeverCallTheSource() {
        KafkaLagSource source = mock(KafkaLagSource.class);
        new ConsumerLagMonitor(source, metrics, 15_000);
        assertUnknown();
        assertThat(gauge("payments.kafka.lag.sample.success")).isZero();
        advance(20);
        assertThat(gauge("payments.kafka.lag.sample.age")).isEqualTo(20);
        registry.getMeters().forEach(meter -> meter.measure().forEach(measurement -> measurement.getValue()));
        verifyNoInteractions(source);
        assertThat(registry.getMeters()).hasSize(11);
        for (Meter meter : registry.getMeters()) {
            assertThat(meter.getId().getTags()).allSatisfy(tag ->
                    assertThat(tag.getKey()).isIn("group", "topic"));
            assertThat(meter.getId().getTag("group")).isEqualTo("payments");
            if (meter.getId().getName().startsWith("payments.kafka.consumer.")) {
                assertThat(meter.getId().getTag("topic")).isIn(TOPICS);
            }
        }
    }

    @Test
    void failuresInvalidateTheWholeSamplePreserveItsAgeAndRecover() throws Exception {
        KafkaLagSource source = mock(KafkaLagSource.class);
        when(source.read()).thenReturn(sample(6, 4)).thenThrow(new IllegalStateException("broker unavailable"))
                .thenReturn(sample(0, 0));
        ConsumerLagMonitor monitor = new ConsumerLagMonitor(source, metrics, 15_000);

        advance(5);
        monitor.sample();
        assertThat(topicGauge("payments.kafka.consumer.lag", "ledger")).isEqualTo(6);
        assertThat(topicGauge("payments.kafka.consumer.lag.max", "ledger")).isEqualTo(4);
        assertThat(gauge("payments.kafka.lag.sample.success")).isEqualTo(1);
        assertThat(gauge("payments.kafka.lag.sample.age")).isZero();
        advance(10);
        monitor.sample();
        assertUnknown();
        assertThat(gauge("payments.kafka.lag.sample.success")).isZero();
        assertThat(gauge("payments.kafka.lag.sample.age")).isEqualTo(10);
        assertThat(registry.get("payments.kafka.lag.sample.failures").counter().count()).isEqualTo(1);
        advance(5);
        monitor.sample();
        assertThat(topicGauge("payments.kafka.consumer.lag", "ledger")).isZero();
        assertThat(gauge("payments.kafka.lag.sample.success")).isEqualTo(1);
        assertThat(gauge("payments.kafka.lag.sample.age")).isZero();
        assertThat(registry.get("payments.kafka.lag.sample.failures").counter().count()).isEqualTo(1);
    }

    @Test
    void anExpiredSampleBecomesUnknownWithoutRequiringAnotherPoll() {
        metrics.recordSuccess(sample(6, 4));
        advance(59);
        assertThat(gauge("payments.kafka.lag.sample.success")).isEqualTo(1);
        advance(1);
        assertUnknown();
        assertThat(gauge("payments.kafka.lag.sample.success")).isZero();
        assertThat(gauge("payments.kafka.lag.sample.age")).isEqualTo(60);
        assertThat(registry.get("payments.kafka.lag.sample.failures").counter().count()).isZero();
        metrics.recordSuccess(sample(0, 0));
        assertThat(gauge("payments.kafka.lag.sample.success")).isEqualTo(1);
    }

    @Test
    void partialOrUnexpectedTopicsInvalidateTheSampleWithoutCreatingMeters() throws Exception {
        KafkaLagSource source = mock(KafkaLagSource.class);
        when(source.read()).thenReturn(Map.of("ledger", new TopicLag(1, 1, 0, 0)))
                .thenReturn(Map.of("ledger", new TopicLag(1, 1, 0, 0),
                        "holds", new TopicLag(0, 0, 0, 0), "unexpected", new TopicLag(9, 9, 0, 0)));
        ConsumerLagMonitor monitor = new ConsumerLagMonitor(source, metrics, 15_000);
        metrics.recordSuccess(sample(6, 4));
        monitor.sample();
        assertUnknown();
        monitor.sample();
        assertUnknown();
        assertThat(registry.getMeters()).hasSize(11);
        assertThat(registry.get("payments.kafka.lag.sample.failures").counter().count()).isEqualTo(2);
    }

    @Test
    void aSuccessfulReadCanExposeInvalidOffsetsAndUncommittedPartitions() {
        metrics.recordSuccess(Map.of("ledger", new TopicLag(Double.NaN, Double.NaN, 2, 1),
                "holds", new TopicLag(0, 0, 0, 0)));
        assertThat(topicGauge("payments.kafka.consumer.lag", "ledger")).isNaN();
        assertThat(topicGauge("payments.kafka.consumer.invalid.offsets", "ledger")).isEqualTo(1);
        assertThat(topicGauge("payments.kafka.consumer.uncommitted.partitions", "ledger")).isEqualTo(2);
        assertThat(gauge("payments.kafka.lag.sample.success")).isEqualTo(1);
    }

    @Test
    void interruptionInvalidatesTheSampleAndPreservesTheInterruptFlag() {
        ConsumerLagMonitor monitor = new ConsumerLagMonitor(() -> {
            throw new InterruptedException("shutdown");
        }, metrics, 15_000);
        try {
            monitor.sample();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertUnknown();
            assertThat(gauge("payments.kafka.lag.sample.success")).isZero();
        } finally {
            Thread.interrupted();
        }
    }

    private void assertUnknown() {
        for (String name : TOPIC_METRICS) {
            for (String topic : TOPICS) {
                assertThat(topicGauge(name, topic)).as("%s/%s", name, topic).isNaN();
            }
        }
    }

    private void advance(long seconds) {
        now.addAndGet(Duration.ofSeconds(seconds).toNanos());
    }

    private double gauge(String name) {
        return registry.get(name).tag("group", "payments").gauge().value();
    }

    private double topicGauge(String name, String topic) {
        return registry.get(name).tags("group", "payments", "topic", topic).gauge().value();
    }

    private static Map<String, TopicLag> sample(double lag, double max) {
        return Map.of("ledger", new TopicLag(lag, max, 0, 0), "holds", new TopicLag(0, 0, 0, 0));
    }
}
