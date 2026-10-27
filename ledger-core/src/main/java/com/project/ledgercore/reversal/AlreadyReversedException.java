package com.project.ledgercore.reversal;

import com.project.ledgercore.common.ConflictException;
import java.util.UUID;

public class AlreadyReversedException extends ConflictException {

    public AlreadyReversedException(UUID transactionId, UUID reversalId) {
        super("already-reversed", "Transaction " + transactionId + " was already reversed by transaction " + reversalId);
    }
}