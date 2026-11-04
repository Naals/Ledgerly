package com.project.paymentservice.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically asks the ledger about authorizations whose time has run out, so they do not stay AUTHORIZED forever. */
@Component
@ConditionalOnProperty(name = "payments.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class AuthorizationReconciliationJob {

    private final PaymentService payments;
    private final int batchSize;

    public AuthorizationReconciliationJob(PaymentService payments,
                                          @Value("${payments.reconciliation.batch-size:50}") int batchSize) {
        this.payments = payments;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${payments.reconciliation.poll-interval-ms:60000}")
    public void reconcileAuthorizations() {
        payments.reconcileAuthorizations(batchSize);
    }
}