package com.project.paymentservice.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration
@ConditionalOnProperty(name = "payments.kafka.enabled", havingValue = "true", matchIfMissing = true)
class KafkaConfiguration {

    static final String DEAD_LETTER_SUFFIX = ".DLT";

    /**
     * A failing event is retried 6 times, 1, 2, 4, 8, 16 and 30 seconds apart (about a minute), then moved to the
     * dead-letter topic ({@code <topic>.DLT}). The minute matters: a reversal that beats its payment to
     * COMPLETED needs a few seconds. An event that cannot even be read will never become readable, so it goes
     * to the dead-letter topic without retries.
     *
     * <p>The dead-letter record is sent with partition -1, which lets the producer pick the partition. The default
     * would reuse the source record's partition number and fail whenever the dead-letter topic has fewer partitions
     * than the topic it mirrors.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(record.topic() + DEAD_LETTER_SUFFIX, -1));

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(6);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(30_000L);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }

    /** The dead-letter topic of the ledger's events exists from the start, with one partition (a record's key picks it). */
    @Bean
    NewTopic ledgerEventsDeadLetterTopic(@Value("${payments.kafka.ledger-topic}") String ledgerTopic) {
        return TopicBuilder.name(ledgerTopic + DEAD_LETTER_SUFFIX).partitions(1).replicas(1).build();
    }

    /** The dead-letter topic of the hold events exists from the start too. */
    @Bean
    NewTopic holdEventsDeadLetterTopic(@Value("${payments.kafka.hold-topic}") String holdTopic) {
        return TopicBuilder.name(holdTopic + DEAD_LETTER_SUFFIX).partitions(1).replicas(1).build();
    }
}