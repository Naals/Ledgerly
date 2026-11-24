package com.project.paymentservice.events.lag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.kafka.clients.admin.Admin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.TaskScheduler;

class ConsumerLagConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withPropertyValues("spring.kafka.admin.auto-create=false",
                    "spring.kafka.consumer.auto-offset-reset=earliest")
            .withUserConfiguration(ConsumerLagConfiguration.class);

    @ParameterizedTest
    @ValueSource(strings = {"payments.kafka.enabled=false", "payments.kafka.lag.enabled=false"})
    void eitherDisableFlagRemovesAllMonitoringBeans(String property) {
        runner.withPropertyValues(property, "spring.kafka.consumer.auto-offset-reset=latest").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ConsumerLagMonitor.class)
                    .doesNotHaveBean(ConsumerLagMetrics.class).doesNotHaveBean(KafkaLagSource.class)
                    .doesNotHaveBean(ConsumerLagProperties.class)
                    .doesNotHaveBean(ConsumerLagConfiguration.ManagedLagAdmin.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring.kafka.consumer.auto-offset-reset=latest",
            "spring.kafka.consumer.auto-offset-reset=none",
            "spring.kafka.consumer.properties[auto.offset.reset]=latest",
            "spring.kafka.consumer.properties[auto.offset.reset]=none"})
    void unsupportedEffectiveResetPoliciesFailBeforeCreatingTheMonitor(String override) {
        runner.withPropertyValues(override).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("Consumer lag monitoring requires "
                            + "spring.kafka.consumer.auto-offset-reset=earliest; "
                            + "disable payments.kafka.lag.enabled to use another policy");
        });
    }

    @Test
    void anAbsentResetPolicyCannotSilentlyUseKafkasLatestDefault() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
                .withPropertyValues("spring.kafka.admin.auto-create=false")
                .withUserConfiguration(ConsumerLagConfiguration.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("spring.kafka.consumer.auto-offset-reset=earliest");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring.kafka.consumer.auto-offset-reset=earliest",
            "spring.kafka.consumer.properties[auto.offset.reset]=earliest"})
    void enabledMonitorUsesEffectiveEarliestPolicyAdminSettingsAndBoundedClose(String resetOverride) {
        Map<String, Object> configuration = Map.of("bootstrap.servers", "broker:9092",
                "security.protocol", "SASL_SSL", "sasl.mechanism", "PLAIN");
        KafkaAdmin kafkaAdmin = mock(KafkaAdmin.class);
        when(kafkaAdmin.getConfigurationProperties()).thenReturn(configuration);
        Admin admin = mock(Admin.class);
        try (MockedStatic<Admin> factory = mockStatic(Admin.class)) {
            factory.when(() -> Admin.create(configuration)).thenReturn(admin);
            runner.withPropertyValues("spring.kafka.consumer.auto-offset-reset=latest")
                    .withBean(KafkaAdmin.class, () -> kafkaAdmin)
                    .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                    .withPropertyValues(resetOverride, "payments.kafka.enabled=true", "payments.kafka.lag.enabled=true",
                            "spring.kafka.consumer.group-id=custom-payments",
                            "payments.kafka.ledger-topic=custom-ledger", "payments.kafka.hold-topic=custom-holds",
                            "payments.kafka.lag.timeout=3s")
                    .run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(ConsumerLagMonitor.class)
                                .hasSingleBean(KafkaLagSource.class);
                        ConsumerLagProperties properties = context.getBean(ConsumerLagProperties.class);
                        assertThat(properties.getPollIntervalMs()).isEqualTo(15_000);
                        assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(3));
                        assertThat(properties.getMaxStaleness()).isEqualTo(Duration.ofSeconds(60));
                        MeterRegistry registry = context.getBean(MeterRegistry.class);
                        assertThat(registry.get("payments.kafka.consumer.lag")
                                .tags("group", "custom-payments", "topic", "custom-ledger").gauge().value()).isNaN();
                        assertThat(registry.get("payments.kafka.consumer.lag")
                                .tags("group", "custom-payments", "topic", "custom-holds").gauge().value()).isNaN();
                        // A shared scheduler bean would redirect the existing unqualified @Scheduled jobs.
                        assertThat(context).doesNotHaveBean(TaskScheduler.class)
                                .doesNotHaveBean(ScheduledExecutorService.class);
                    });
            factory.verify(() -> Admin.create(configuration));
        }
        verify(admin).close(Duration.ofSeconds(3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"payments.kafka.lag.poll-interval-ms=0", "payments.kafka.lag.poll-interval-ms=-1",
            "payments.kafka.lag.timeout=0s", "payments.kafka.lag.timeout=-1s",
            "payments.kafka.lag.timeout=1ns", "payments.kafka.lag.timeout=30d",
            "payments.kafka.lag.max-staleness=0s", "payments.kafka.lag.max-staleness=15s"})
    void invalidTimingFailsAtStartup(String property) {
        KafkaAdmin kafkaAdmin = mock(KafkaAdmin.class);
        runner.withBean(KafkaAdmin.class, () -> kafkaAdmin)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues(property).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("payments.kafka.lag");
                });
    }
}
