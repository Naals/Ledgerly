package com.project.ledgercore.common;

/** Base type for expected, business-level failures. Each one carries a stable, machine-readable code. */
public abstract class LedgerException extends RuntimeException {

    private final String code;

    protected LedgerException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** Stable identifier of the problem, for example {@code insufficient-funds}. Exposed to API clients. */
    public String code() {
        return code;
    }
}