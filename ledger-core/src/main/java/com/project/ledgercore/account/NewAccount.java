package com.project.ledgercore.account;

import java.util.Currency;
import java.util.Objects;

/** The data needed to open an account. Validated on construction, in line with the database constraints. */
public record NewAccount(String ownerRef, AccountType type, Currency currency, boolean allowNegative) {

    static final int MAX_OWNER_REF_LENGTH = 64;

    public NewAccount {
        Objects.requireNonNull(ownerRef, "ownerRef must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (ownerRef.isBlank() || ownerRef.length() > MAX_OWNER_REF_LENGTH) {
            throw new IllegalArgumentException("ownerRef must be 1-" + MAX_OWNER_REF_LENGTH + " characters");
        }
        if (allowNegative && type != AccountType.SYSTEM) {
            throw new IllegalArgumentException("only SYSTEM accounts may go negative");
        }
    }

    public static NewAccount customer(String ownerRef, Currency currency) {
        return new NewAccount(ownerRef, AccountType.CUSTOMER, currency, false);
    }

    public static NewAccount system(String ownerRef, Currency currency) {
        return new NewAccount(ownerRef, AccountType.SYSTEM, currency, true);
    }
}
