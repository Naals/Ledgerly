package com.project.paymentservice.ledger;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A ledger hold as this service sees it.
 *
 * @param ledgerTransactionId the transfer that captured it; null unless the hold was captured
 */
public record HoldInfo(UUID id, HoldState state, OffsetDateTime expiresAt, UUID ledgerTransactionId) {
}