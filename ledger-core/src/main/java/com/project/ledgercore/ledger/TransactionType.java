package com.project.ledgercore.ledger;

public enum TransactionType {
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER,
    /** A new transaction that exactly undoes an earlier one. */
    REVERSAL
}