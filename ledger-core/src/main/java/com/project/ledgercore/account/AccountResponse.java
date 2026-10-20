package com.project.ledgercore.account;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String ownerRef,
        AccountType type,
        String currency,
        AccountStatus status,
        OffsetDateTime createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.id(),
                account.ownerRef(),
                account.type(),
                account.currency().getCurrencyCode(),
                account.status(),
                account.createdAt());
    }
}
