package com.project.paymentservice.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes events that were delivered long ago, so the outbox does not grow forever. Undelivered events are never touched. */
@Component
@ConditionalOnProperty(name = "payments.outbox.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxCleanup {

    static final int BATCH = 1_000;

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanup.class);

    private final OutboxRepository outbox;
    private final int retentionDays;

    public OutboxCleanup(OutboxRepository outbox, @Value("${payments.outbox.cleanup.retention-days:7}") int retentionDays) {
        this.outbox = outbox;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${payments.outbox.cleanup.cron:0 17 3 * * *}")
    public void deleteDeliveredEvents() {
        int deleted;
        int total = 0;
        do {
            deleted = outbox.deletePublishedOlderThan(retentionDays, BATCH);
            total += deleted;
        } while (deleted == BATCH);
        if (total > 0) {
            log.info("Deleted {} outbox events delivered more than {} days ago", total, retentionDays);
        }
    }
}