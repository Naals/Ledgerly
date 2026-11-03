package com.project.ledgercore.hold;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns scheduling on for the expiry job, independently of the outbox relay. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "ledger.holds.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class HoldConfiguration {
}