package com.project.ledgercore.account;

import com.project.ledgercore.common.ConflictException;
import java.util.UUID;

public class InvalidStatusTransitionException extends ConflictException {

    public InvalidStatusTransitionException(UUID accountId, AccountStatus from, AccountStatus to) {
        super("invalid-status-transition", "Account " + accountId + " cannot go from " + from + " to " + to);
    }
}