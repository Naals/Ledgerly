package com.project.ledgercore.ledger;

import com.project.ledgercore.common.Money;

/**
 * What an account holds and what of it can be spent: {@code available = balance - held}.
 * For the bank's own accounts nothing is ever held.
 */
public record BalanceSummary(Money balance, Money held) {

    public BalanceSummary {
        if (!balance.currency().equals(held.currency())) {
            throw new IllegalArgumentException("balance and held amount must share a currency");
        }
    }

    public Money available() {
        return balance.minus(held);
    }
}