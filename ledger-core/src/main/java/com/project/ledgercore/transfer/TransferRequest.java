package com.project.ledgercore.transfer;

import com.project.ledgercore.common.Currencies;
import com.project.ledgercore.common.Money;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record TransferRequest(
        @NotNull UUID fromAccountId,
        @NotNull UUID toAccountId,
        @Positive long amountMinor,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter ISO 4217 code in upper case")
        String currency,
        @Size(max = 64) @Pattern(regexp = ".*\\S.*", message = "must not be blank")
        String reference) {

    Money toMoney() {
        return new Money(amountMinor, Currencies.parse(currency));
    }
}