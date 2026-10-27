package com.project.ledgercore.reversal;

import com.project.ledgercore.common.BusinessRuleException;
import java.util.UUID;

public class NotReversibleException extends BusinessRuleException {

    public NotReversibleException(UUID transactionId) {
        super("cannot-reverse-a-reversal",
                "Transaction " + transactionId + " is itself a reversal and cannot be reversed");
    }
}