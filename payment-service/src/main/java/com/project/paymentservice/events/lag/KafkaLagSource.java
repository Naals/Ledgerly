package com.project.paymentservice.events.lag;

import java.util.Map;

@FunctionalInterface
public interface KafkaLagSource {
    Map<String, TopicLag> read() throws Exception;
}
