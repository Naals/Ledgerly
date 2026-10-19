package com.project.ledgercore.ledger;

import com.project.ledgercore.common.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * One line of a posting: a signed amount applied to one account.
 * A positive amount credits the account (balance goes up), a negative amount debits it.
 */
public record PostingLine(UUID accountId, Money amount) {

    public PostingLine {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
    }

    /** Money flows into the account. {@code amount} must be positive. */
    public static PostingLine credit(UUID accountId, Money amount) {
        requirePositive(amount);
        return new PostingLine(accountId, amount);
    }

    /** Money flows out of the account. {@code amount} must be positive; it is stored negated. */
    public static PostingLine debit(UUID accountId, Money amount) {
        requirePositive(amount);
        return new PostingLine(accountId, amount.negate());
    }

    private static void requirePositive(Money amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("amount must be positive, was " + amount);
        }
    }
}
