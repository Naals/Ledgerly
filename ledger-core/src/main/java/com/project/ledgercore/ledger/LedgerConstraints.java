package com.project.ledgercore.ledger;

/** Names of database constraints that the application translates into business errors. */
final class LedgerConstraints {

    /** CHECK (allow_negative OR balance >= 0) on accounts, added in V7. */
    static final String NON_NEGATIVE_BALANCE = "ck_accounts_balance_non_negative";

    private LedgerConstraints() {
    }

    /** True if the error, or any of its causes, reports a violation of the no-overdraft constraint. */
    static boolean isOverdraftViolation(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause() == current ? null : current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains(NON_NEGATIVE_BALANCE)) {
                return true;
            }
        }
        return false;
    }
}