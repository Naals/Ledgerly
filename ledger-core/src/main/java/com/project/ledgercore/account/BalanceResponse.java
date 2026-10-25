package com.project.ledgercore.account;

import com.project.ledgercore.common.Money;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A balance, either the current one ({@code asOf} is null) or the balance at a past moment. */
public record BalanceResponse(UUID accountId, long amountMinor, String currency, OffsetDateTime asOf) {

    public static BalanceResponse from(UUID accountId, Money balance) {
        return new BalanceResponse(accountId, balance.minorUnits(), balance.currency().getCurrencyCode(), null);
    }

    public static BalanceResponse from(UUID accountId, Money balance, OffsetDateTime asOf) {
        return new BalanceResponse(accountId, balance.minorUnits(), balance.currency().getCurrencyCode(), asOf);
    }
}