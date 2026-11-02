package com.project.paymentservice.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration
@ConditionalOnProperty(name = "payments.kafka.enabled", havingValue = "true", matchIfMissing = true)
class KafkaConfiguration {

    /**
     * A failing event is retried 6 times, 1, 2, 4, 8, 16 and 30 seconds apart (about a minute), then moved to the
     * dead-letter topic ({@code <topic>.DLT}). The minute matters: a reversal that beats its payment to
     * COMPLETED needs a few seconds. An event that cannot even be read will never become readable, so it goes
     * to the dead-letter topic without retries.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(6);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(30_000L);

        DefaultErrorHandler handler = new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), backOff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }
}