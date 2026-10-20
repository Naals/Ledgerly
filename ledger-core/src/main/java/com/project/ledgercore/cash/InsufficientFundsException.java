package com.project.ledgercore.cash;

import com.project.ledgercore.common.BusinessRuleException;
import com.project.ledgercore.common.Money;
import java.util.UUID;

public class InsufficientFundsException extends BusinessRuleException {

    public InsufficientFundsException(UUID accountId, Money balance, Money requested) {
        super("insufficient-funds",
                "Account " + accountId + " has " + balance + " available, but " + requested + " was requested");
    }
}
