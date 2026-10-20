package com.project.ledgercore.common;

import java.util.Currency;

public final class Currencies {

    private Currencies() {
    }

    /** Parses an ISO 4217 currency code, turning unknown codes into a client error instead of a crash. */
    public static Currency parse(String code) {
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid-currency", "Unknown currency code: " + code);
        }
    }
}
