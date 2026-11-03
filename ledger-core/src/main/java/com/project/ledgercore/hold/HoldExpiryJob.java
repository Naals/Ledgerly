package com.project.ledgercore.hold;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically frees the funds of holds that nobody captured in time. */
@Component
@ConditionalOnProperty(name = "ledger.holds.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class HoldExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);

    private final HoldService holds;
    private final int batchSize;

    public HoldExpiryJob(HoldService holds, @Value("${ledger.holds.expiry.batch-size:100}") int batchSize) {
        this.holds = holds;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${ledger.holds.expiry.interval-ms:30000}")
    public void expireDueHolds() {
        try {
            int expired;
            do {
                expired = holds.expireDue(batchSize);
                if (expired > 0) {
                    log.info("Expired {} holds", expired);
                }
            } while (expired == batchSize);
        } catch (RuntimeException e) {
            log.warn("Hold expiry run failed; it will try again on the next run", e);
        }
    }
}