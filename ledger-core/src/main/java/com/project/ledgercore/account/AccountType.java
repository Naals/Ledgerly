package com.project.ledgercore.account;

public enum AccountType {
    /** Belongs to a bank customer. Can never go below zero. */
    CUSTOMER,
    /** Belongs to the bank itself (cash-in-bank, fees, ...). May go negative. */
    SYSTEM
}