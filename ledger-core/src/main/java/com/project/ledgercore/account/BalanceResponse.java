package com.project.ledgercore.account;

import com.project.ledgercore.common.Money;
import java.util.UUID;

public record BalanceResponse(UUID accountId, long amountMinor, String currency) {

    public static BalanceResponse from(UUID accountId, Money balance) {
        return new BalanceResponse(accountId, balance.minorUnits(), balance.currency().getCurrencyCode());
    }
}