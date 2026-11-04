package com.project.paymentservice.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns scheduling on for the reconciliation job, independently of the retry job. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "payments.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
class PaymentReconciliationConfiguration {
}