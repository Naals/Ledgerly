package com.project.ledgercore.ledger;

/** Names of database constraints that the application translates into business errors. */
final class LedgerConstraints {

    /** CHECK (allow_negative OR balance >= 0) on accounts, added in V7. */
    static final String NON_NEGATIVE_BALANCE = "ck_accounts_balance_non_negative";

    /** Marker in the message the entry trigger raises for a FROZEN or CLOSED account (V10). */
    static final String ACCOUNT_NOT_ACTIVE = "account_not_active";

    /** CHECK (status <> 'CLOSED' OR balance = 0) on accounts, added in V10. */
    static final String CLOSED_IS_EMPTY = "ck_accounts_closed_is_empty";

    private LedgerConstraints() {
    }

    /** True if the error, or any of its causes, reports a violation of the no-overdraft constraint. */
    static boolean isOverdraftViolation(Throwable error) {
        return reports(error, NON_NEGATIVE_BALANCE);
    }

    /** True if the error says an entry was refused because its account is frozen or closed. */
    static boolean isInactiveAccountViolation(Throwable error) {
        return reports(error, ACCOUNT_NOT_ACTIVE) || reports(error, CLOSED_IS_EMPTY);
    }

    private static boolean reports(Throwable error, String fragment) {
        for (Throwable current = error; current != null; current = current.getCause() == current ? null : current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}