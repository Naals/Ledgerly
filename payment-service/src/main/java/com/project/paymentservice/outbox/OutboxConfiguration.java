package com.project.paymentservice.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns scheduling on, but only when the relay is enabled. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "payments.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxConfiguration {
}