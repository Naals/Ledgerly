package com.project.ledgercore.cash;

import com.project.ledgercore.common.Currencies;
import com.project.ledgercore.common.Money;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** An amount in minor units (tiyn for KZT) and its ISO 4217 currency code. */
public record CashRequest(
        @Positive long amountMinor,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter ISO 4217 code in upper case")
        String currency) {

    Money toMoney() {
        return new Money(amountMinor, Currencies.parse(currency));
    }
}