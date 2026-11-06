package com.project.paymentservice.payment;

import com.project.paymentservice.events.EventMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The safety net behind the ledger's hold events. The events normally settle an authorization within seconds of its hold
 * ending; this job only looks at authorizations that ended more than the grace period ago, so anything it finds is
 * something the events missed, and it says so.
 */
@Component
@ConditionalOnProperty(name = "payments.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class AuthorizationReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationReconciliationJob.class);

    private final PaymentService payments;
    private final EventMetrics metrics;
    private final int batchSize;
    private final int graceSeconds;

    public AuthorizationReconciliationJob(PaymentService payments, EventMetrics metrics,
                                          @Value("${payments.reconciliation.batch-size:50}") int batchSize,
                                          @Value("${payments.reconciliation.grace-seconds:300}") int graceSeconds) {
        this.payments = payments;
        this.metrics = metrics;
        this.batchSize = batchSize;
        this.graceSeconds = graceSeconds;
    }

    @Scheduled(fixedDelayString = "${payments.reconciliation.poll-interval-ms:60000}")
    public void reconcileAuthorizations() {
        int settled = payments.reconcileAuthorizations(graceSeconds, batchSize);
        if (settled > 0) {
            log.warn("Reconciliation settled {} authorizations that the hold events had not: check the event path", settled);
            metrics.reconciled(settled);
        }
    }
}