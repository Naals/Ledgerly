package com.project.ledgercore.account;

import com.project.ledgercore.common.BusinessRuleException;
import java.util.Currency;
import java.util.UUID;

public class CurrencyMismatchException extends BusinessRuleException {

    public CurrencyMismatchException(UUID accountId, Currency accountCurrency, Currency requested) {
        super("currency-mismatch", "Account " + accountId + " is held in "
                + accountCurrency.getCurrencyCode() + ", not " + requested.getCurrencyCode());
    }
}
