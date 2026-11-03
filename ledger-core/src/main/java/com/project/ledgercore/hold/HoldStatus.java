package com.project.ledgercore.hold;

/** Where a hold is in its life. It leaves ACTIVE exactly once and then never changes (V15 enforces the same). */
public enum HoldStatus {
    /** The funds are reserved. */
    ACTIVE,
    /** The funds, or part of them, were taken: a ledger transaction moved them to the payee. */
    CAPTURED,
    /** The payer's side cancelled it; the funds are free again. */
    RELEASED,
    /** Nobody captured it in time; the funds are free again. */
    EXPIRED;

    public boolean canMoveTo(HoldStatus target) {
        return this == ACTIVE && target != ACTIVE;
    }
}