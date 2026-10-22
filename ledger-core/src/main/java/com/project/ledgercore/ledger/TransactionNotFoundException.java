package com.project.ledgercore.ledger;

import com.project.ledgercore.common.NotFoundException;
import java.util.UUID;

public class TransactionNotFoundException extends NotFoundException {

    public TransactionNotFoundException(UUID transactionId) {
        super("transaction-not-found", "Transaction " + transactionId + " does not exist");
    }
}
