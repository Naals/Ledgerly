package com.project.paymentservice.payment;

/** What came of asking the ledger about an authorized payment's hold. */
public enum Settlement {
    /** The hold had ended (captured, released or expired) and the payment now says so. */
    SETTLED,
    /** The hold is still active: nothing to record yet. */
    STILL_ACTIVE,
    /** The ledger could not answer; try again later. */
    LEDGER_UNAVAILABLE,
    /** The ledger does not know the hold, or reported a state that cannot be recorded. Needs a human. */
    UNKNOWN_HOLD,
    /** The payment is not AUTHORIZED, so there is nothing to settle. */
    NOT_AUTHORIZED
}