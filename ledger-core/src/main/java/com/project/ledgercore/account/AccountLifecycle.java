package com.project.ledgercore.account;

import java.util.UUID;

/** The allowed changes of an account's status. */
public final class AccountLifecycle {

    private AccountLifecycle() {
    }

    /**
     * ACTIVE and FROZEN can switch back and forth, either can be CLOSED, and CLOSED is final.
     * Asking for the status the account already has is always allowed and changes nothing.
     */
    public static boolean isAllowed(AccountStatus from, AccountStatus to) {
        if (from == to) {
            return true;
        }
        return switch (from) {
            case ACTIVE -> to == AccountStatus.FROZEN || to == AccountStatus.CLOSED;
            case FROZEN -> to == AccountStatus.ACTIVE || to == AccountStatus.CLOSED;
            case CLOSED -> false;
        };
    }

    public static void requireAllowed(UUID accountId, AccountStatus from, AccountStatus to) {
        if (!isAllowed(from, to)) {
            throw new InvalidStatusTransitionException(accountId, from, to);
        }
    }
}