package com.project.paymentservice.events.lag;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns its scheduler so Kafka timeouts cannot delay payment retry or outbox jobs. */
public final class ConsumerLagMonitor implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ConsumerLagMonitor.class);
    private final KafkaLagSource source;
    private final ConsumerLagMetrics metrics;
    private final long pollIntervalMs;
    private ScheduledExecutorService scheduler;

    public ConsumerLagMonitor(KafkaLagSource source, ConsumerLagMetrics metrics, long pollIntervalMs) {
        this.source = source;
        this.metrics = metrics;
        this.pollIntervalMs = pollIntervalMs;
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "payment-consumer-lag");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::sample, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    public void sample() {
        try {
            metrics.recordSuccess(source.read());
        } catch (Exception failure) {
            metrics.recordFailure();
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Kafka consumer lag sample failed: {}", failure.toString());
        }
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
