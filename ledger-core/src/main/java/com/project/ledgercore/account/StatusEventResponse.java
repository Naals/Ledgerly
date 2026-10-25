package com.project.ledgercore.account;

import java.time.OffsetDateTime;

public record StatusEventResponse(long id, String from, String to, String reason, OffsetDateTime createdAt) {

    static StatusEventResponse from(AccountStatusEvent event) {
        return new StatusEventResponse(
                event.id(), event.from().name(), event.to().name(), event.reason(), event.createdAt());
    }
}