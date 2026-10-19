package com.project.ledgercore.account;

import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.UUID;

public record Account(
        UUID id,
        String ownerRef,
        AccountType type,
        Currency currency,
        AccountStatus status,
        boolean allowNegative,
        OffsetDateTime createdAt) {
}