package com.project.paymentservice.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns scheduling on, but only when the retry job is enabled. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "payments.retry.enabled", havingValue = "true", matchIfMissing = true)
class PaymentRetryConfiguration {
}