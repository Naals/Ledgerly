package com.project.ledgercore.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the outbox and keeps delivering until nothing more is due. */
@Component
@ConditionalOnProperty(name = "ledger.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxPublisher publisher;
    private final int batchSize;

    public OutboxRelay(OutboxPublisher publisher, @Value("${ledger.outbox.relay.batch-size:100}") int batchSize) {
        this.publisher = publisher;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${ledger.outbox.relay.poll-interval-ms:1000}")
    public void deliverPendingEvents() {
        try {
            int delivered;
            do {
                delivered = publisher.publishBatch(batchSize);
            } while (delivered == batchSize);
        } catch (RuntimeException e) {
            log.warn("Outbox relay run failed; it will try again on the next poll", e);
        }
    }
}