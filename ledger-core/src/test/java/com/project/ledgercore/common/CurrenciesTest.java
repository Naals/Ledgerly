package com.project.ledgercore.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import org.junit.jupiter.api.Test;

class CurrenciesTest {

    @Test
    void parsesKnownIsoCodes() {
        assertThat(Currencies.parse("KZT")).isEqualTo(Currency.getInstance("KZT"));
        assertThat(Currencies.parse("USD").getCurrencyCode()).isEqualTo("USD");
    }

    @Test
    void unknownCodesBecomeABadRequestWithAStableCode() {
        assertThatThrownBy(() -> Currencies.parse("ZZZ"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("ZZZ")
                .extracting(e -> ((BadRequestException) e).code())
                .isEqualTo("invalid-currency");
    }

    @Test
    void lowerCaseCodesAreRejected() {
        assertThatThrownBy(() -> Currencies.parse("kzt")).isInstanceOf(BadRequestException.class);
    }
}
