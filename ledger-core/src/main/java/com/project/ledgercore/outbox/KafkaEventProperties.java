package com.project.ledgercore.outbox;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Settings of the Kafka publisher: which topic each event type goes to, and how long to wait for the broker. */
@Component
@ConfigurationProperties(prefix = "ledger.outbox.kafka")
public class KafkaEventProperties {

    private Map<String, String> topics = new HashMap<>();
    private Duration sendTimeout = Duration.ofSeconds(10);

    public Map<String, String> getTopics() {
        return topics;
    }

    public void setTopics(Map<String, String> topics) {
        this.topics = topics;
    }

    public Duration getSendTimeout() {
        return sendTimeout;
    }

    public void setSendTimeout(Duration sendTimeout) {
        this.sendTimeout = sendTimeout;
    }
}