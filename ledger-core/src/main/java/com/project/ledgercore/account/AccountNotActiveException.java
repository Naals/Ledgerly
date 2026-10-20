package com.project.ledgercore.account;

import com.project.ledgercore.common.ConflictException;
import java.util.UUID;

public class AccountNotActiveException extends ConflictException {

    public AccountNotActiveException(UUID accountId, AccountStatus status) {
        super("account-not-active",
                "Account " + accountId + " is " + status + " and cannot be used for this operation");
    }
}
