package com.project.ledgercore.idempotency;

import com.project.ledgercore.common.LedgerException;

/** A committed business refusal, including the metadata needed to replay its HTTP response. */
public class IdempotentRejectionException extends LedgerException {

    private final int status;
    private final boolean replayed;

    IdempotentRejectionException(int status, String code, String message, boolean replayed) {
        super(code, message);
        this.status = status;
        this.replayed = replayed;
    }

    public int status() {
        return status;
    }

    public boolean replayed() {
        return replayed;
    }
}
