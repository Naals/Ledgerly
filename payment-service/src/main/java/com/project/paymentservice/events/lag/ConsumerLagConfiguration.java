package com.project.paymentservice.events.lag;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConsumerLagProperties.class)
@ConditionalOnProperty(prefix = "payments.kafka", name = {"enabled", "lag.enabled"},
        havingValue = "true", matchIfMissing = true)
public class ConsumerLagConfiguration {
    public ConsumerLagConfiguration(ConsumerFactory<?, ?> consumerFactory) {
        // Validate the final factory configuration, including low-level properties and customizers.
        // An absent value uses Kafka's latest default, which cannot support retained-backlog estimates.
        Object autoOffsetReset = consumerFactory.getConfigurationProperties()
                .get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG);
        if (!"earliest".equals(autoOffsetReset)) {
            throw new IllegalArgumentException("Consumer lag monitoring requires "
                    + "spring.kafka.consumer.auto-offset-reset=earliest; "
                    + "disable payments.kafka.lag.enabled to use another policy");
        }
    }

    @Bean(destroyMethod = "close")
    ManagedLagAdmin consumerLagAdmin(KafkaAdmin kafkaAdmin, ConsumerLagProperties properties) {
        // Reuse the application's bootstrap and security properties, including SASL/SSL settings.
        return new ManagedLagAdmin(Admin.create(kafkaAdmin.getConfigurationProperties()), properties.getTimeout());
    }

    @Bean
    LagIdentity consumerLagIdentity(
            @Value("${spring.kafka.consumer.group-id:payment-service}") String group,
            @Value("${payments.kafka.ledger-topic:ledger.transaction-posted.v1}") String ledgerTopic,
            @Value("${payments.kafka.hold-topic:ledger.hold-status-changed.v1}") String holdTopic) {
        if (group.isBlank() || ledgerTopic.isBlank() || holdTopic.isBlank()) {
            throw new IllegalArgumentException("Kafka lag group and topic names must not be blank");
        }
        Set<String> topics = new LinkedHashSet<>();
        topics.add(ledgerTopic);
        topics.add(holdTopic);
        return new LagIdentity(group, Set.copyOf(topics));
    }

    @Bean
    KafkaLagSource consumerLagSource(ManagedLagAdmin admin, LagIdentity identity, ConsumerLagProperties properties) {
        return new AdminKafkaLagSource(admin.client(), identity.group(), identity.topics(), properties.getTimeout());
    }

    @Bean
    ConsumerLagMetrics consumerLagMetrics(MeterRegistry registry, LagIdentity identity,
                                          ConsumerLagProperties properties) {
        return new ConsumerLagMetrics(registry, identity.group(), identity.topics(), properties.getMaxStaleness());
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    ConsumerLagMonitor consumerLagMonitor(KafkaLagSource source, ConsumerLagMetrics metrics,
                                          ConsumerLagProperties properties) {
        return new ConsumerLagMonitor(source, metrics, properties.getPollIntervalMs());
    }

    record LagIdentity(String group, Set<String> topics) {}

    record ManagedLagAdmin(Admin client, Duration closeTimeout) implements AutoCloseable {
        @Override
        public void close() {
            client.close(closeTimeout);
        }
    }
}
