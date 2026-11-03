package com.project.paymentservice.ledger;

/** The states of a ledger hold, as the ledger reports them. */
public enum HoldState {
    ACTIVE,
    CAPTURED,
    RELEASED,
    EXPIRED
}