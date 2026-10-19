package com.project.ledgercore.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Test
    void addsAndSubtractsAmountsInTheSameCurrency() {
        Money a = Money.of(1_000, "KZT");
        Money b = Money.of(250, "KZT");

        assertThat(a.plus(b)).isEqualTo(Money.of(1_250, "KZT"));
        assertThat(a.minus(b)).isEqualTo(Money.of(750, "KZT"));
    }

    @Test
    void negateFlipsTheSign() {
        Money credit = Money.of(500, "KZT");

        assertThat(credit.negate()).isEqualTo(Money.of(-500, "KZT"));
        assertThat(credit.negate().isNegative()).isTrue();
        assertThat(credit.isPositive()).isTrue();
        assertThat(Money.zero(KZT).isZero()).isTrue();
    }

    @Test
    void arithmeticAcrossCurrenciesIsRejected() {
        Money kzt = Money.of(100, "KZT");
        Money usd = Money.of(100, "USD");

        assertThatThrownBy(() -> kzt.plus(usd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KZT")
                .hasMessageContaining("USD");
        assertThatThrownBy(() -> kzt.minus(usd)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overflowThrowsInsteadOfWrappingAround() {
        Money max = Money.of(Long.MAX_VALUE, "KZT");

        assertThatThrownBy(() -> max.plus(Money.of(1, "KZT"))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.of(Long.MIN_VALUE, "KZT").negate()).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void unknownCurrencyCodeIsRejected() {
        assertThatThrownBy(() -> Money.of(1, "KZ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(1, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void toStringUsesTheCurrencyFractionDigits() {
        assertThat(Money.of(123_456, "KZT")).hasToString("1234.56 KZT");
        assertThat(Money.of(5, "KZT")).hasToString("0.05 KZT");
        assertThat(Money.of(-250, "USD")).hasToString("-2.50 USD");
        assertThat(Money.of(500, "JPY")).hasToString("500 JPY");
    }
}
