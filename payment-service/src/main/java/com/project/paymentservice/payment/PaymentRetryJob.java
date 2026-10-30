package com.project.paymentservice.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically asks the ledger again about payments it has not answered for. */
@Component
@ConditionalOnProperty(name = "payments.retry.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentRetryJob {

    private final PaymentService payments;
    private final int olderThanSeconds;
    private final int batchSize;

    public PaymentRetryJob(PaymentService payments,
                           @Value("${payments.retry.older-than-seconds:30}") int olderThanSeconds,
                           @Value("${payments.retry.batch-size:50}") int batchSize) {
        this.payments = payments;
        this.olderThanSeconds = olderThanSeconds;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${payments.retry.poll-interval-ms:10000}")
    public void retryPendingPayments() {
        payments.retryPending(olderThanSeconds, batchSize);
    }
}