package com.project.ledgercore.account;


import java.util.Currency;
import org.springframework.stereotype.Service;

/** Access to the bank's own accounts. */
@Service
public class SystemAccounts {

    /** Reference of the bank's cash account; cash deposits and withdrawals post against it. */
    public static final String CASH_IN_BANK = "system:cash-in-bank";

    private final AccountRepository accounts;

    public SystemAccounts(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public Account cashInBank(Currency currency) {
        return accounts.findOrCreateSystem(CASH_IN_BANK, currency);
    }
}