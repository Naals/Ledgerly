package com.project.ledgercore.account;

import java.util.Currency;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class AccountService {

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public Account openCustomerAccount(String ownerRef, Currency currency) {
        return accounts.create(NewAccount.customer(ownerRef, currency));
    }

    public Account get(UUID id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

}
