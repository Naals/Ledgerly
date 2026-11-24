package com.project.paymentservice.events.lag;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "payments.kafka.lag")
public class ConsumerLagProperties {
    @Min(1)
    private long pollIntervalMs = 15_000;
    @NotNull
    private Duration timeout = Duration.ofSeconds(5);
    @NotNull
    private Duration maxStaleness = Duration.ofSeconds(60);

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Duration getMaxStaleness() {
        return maxStaleness;
    }

    public void setMaxStaleness(Duration maxStaleness) {
        this.maxStaleness = maxStaleness;
    }

    @AssertTrue(message = "timeout must be at least 1ms and at most 2147483647ms")
    public boolean isTimeoutValid() {
        return timeout == null || (timeout.compareTo(Duration.ofMillis(1)) >= 0
                && timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) <= 0);
    }

    @AssertTrue(message = "max-staleness must be positive and greater than poll-interval-ms")
    public boolean isMaxStalenessValid() {
        return maxStaleness == null || (!maxStaleness.isNegative() && !maxStaleness.isZero()
                && maxStaleness.compareTo(Duration.ofMillis(pollIntervalMs)) > 0);
    }
}
