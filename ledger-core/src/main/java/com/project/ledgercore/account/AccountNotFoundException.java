package com.project.ledgercore.account;

import com.project.ledgercore.common.NotFoundException;
import java.util.UUID;

public class AccountNotFoundException extends NotFoundException {

    public AccountNotFoundException(UUID accountId) {
        super("account-not-found", "Account " + accountId + " does not exist");
    }
}
