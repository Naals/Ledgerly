package com.project.ledgercore.account;

import java.time.OffsetDateTime;

public record AccountStatusEvent(
        long id, AccountStatus from, AccountStatus to, String reason, OffsetDateTime createdAt) {
}