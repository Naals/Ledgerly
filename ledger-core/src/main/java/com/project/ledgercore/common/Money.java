package com.project.ledgercore.common;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money in the smallest unit of a currency (tiyn for KZT, cents for USD).
 *
 * <p>Amounts are signed: a negative amount is a debit, a positive amount is a credit.
 * Arithmetic is exact: an overflow throws {@link ArithmeticException} instead of wrapping around.
 */
public record Money(long minorUnits, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
    }

    public static Money of(long minorUnits, String currencyCode) {
        return new Money(minorUnits, Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(0, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    public boolean isZero() {
        return minorUnits == 0;
    }

    public boolean isPositive() {
        return minorUnits > 0;
    }

    public boolean isNegative() {
        return minorUnits < 0;
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + currency.getCurrencyCode() + " vs " + other.currency.getCurrencyCode());
        }
    }

    @Override
    public String toString() {
        int fractionDigits = Math.max(currency.getDefaultFractionDigits(), 0);
        return BigDecimal.valueOf(minorUnits, fractionDigits).toPlainString() + " " + currency.getCurrencyCode();
    }
}