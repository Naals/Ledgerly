package com.project.ledgercore.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/** Creates the topics at startup, so partitions are a decision and not whatever the broker defaults to. */
@Configuration
@ConditionalOnProperty(name = "ledger.outbox.publisher", havingValue = "kafka")
class KafkaTopicsConfiguration {

    static final int PARTITIONS = 3;

    @Bean
    KafkaAdmin.NewTopics ledgerTopics(KafkaEventProperties properties) {
        NewTopic[] topics = properties.getTopics().values().stream()
                .distinct()
                .map(name -> TopicBuilder.name(name).partitions(PARTITIONS).replicas(1).build())
                .toArray(NewTopic[]::new);
        return new KafkaAdmin.NewTopics(topics);
    }
}