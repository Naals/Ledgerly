package com.project.paymentservice.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns scheduling on for the cleanup, independently of the relay. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "payments.outbox.cleanup.enabled", havingValue = "true", matchIfMissing = true)
class OutboxCleanupConfiguration {
}