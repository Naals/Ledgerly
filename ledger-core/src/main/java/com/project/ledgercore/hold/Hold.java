package com.project.ledgercore.hold;

import com.project.ledgercore.common.Money;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Funds reserved on {@code accountId} for a payment to {@code toAccountId}.
 *
 * @param expired   true if the hold's time had run out at the moment it was read (by the database's clock)
 * @param captured  what was actually captured, if it was; never more than {@code amount}
 */
public record Hold(
        UUID id,
        UUID accountId,
        UUID toAccountId,
        Money amount,
        HoldStatus status,
        String reference,
        OffsetDateTime expiresAt,
        boolean expired,
        Money captured,
        UUID ledgerTransactionId,
        OffsetDateTime createdAt) {
}