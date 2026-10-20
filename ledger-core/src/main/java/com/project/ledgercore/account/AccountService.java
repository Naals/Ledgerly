package com.project.ledgercore.account;

import java.util.Currency;
import java.util.UUID;

import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.EntryPage;
import com.project.ledgercore.ledger.LedgerService;
import org.springframework.stereotype.Service;

@Service
public class AccountService {


    private final AccountRepository accounts;
    private final LedgerService ledger;

    public AccountService(AccountRepository accounts, LedgerService ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    public Money balanceOf(UUID id) {
        return ledger.balanceOf(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    public Account openCustomerAccount(String ownerRef, Currency currency) {
        return accounts.create(NewAccount.customer(ownerRef, currency));
    }

    public Account get(UUID id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    public EntryPage entriesOf(UUID id, Long beforeId, int limit) {
        get(id);   // 404 if the account does not exist
        return ledger.entriesOf(id, beforeId, limit);
    }

}
