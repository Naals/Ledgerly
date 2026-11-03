package com.project.ledgercore.account;

import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.BalanceSummary;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A balance. The current one ({@code asOf} is null) also says what is held and what is available. A balance at a
 * past moment says neither: what was held back then is not recorded.
 */
public record BalanceResponse(
        UUID accountId, long amountMinor, String currency, OffsetDateTime asOf, Long heldMinor, Long availableMinor) {

    public static BalanceResponse from(UUID accountId, BalanceSummary summary) {
        return new BalanceResponse(
                accountId,
                summary.balance().minorUnits(),
                summary.balance().currency().getCurrencyCode(),
                null,
                summary.held().minorUnits(),
                summary.available().minorUnits());
    }

    /** When only the balance is known. */
    public static BalanceResponse from(UUID accountId, Money balance) {
        return new BalanceResponse(accountId, balance.minorUnits(), balance.currency().getCurrencyCode(), null, null, null);
    }

    public static BalanceResponse from(UUID accountId, Money balance, OffsetDateTime asOf) {
        return new BalanceResponse(accountId, balance.minorUnits(), balance.currency().getCurrencyCode(), asOf, null, null);
    }
}