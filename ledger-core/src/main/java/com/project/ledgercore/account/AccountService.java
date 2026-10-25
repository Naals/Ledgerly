package com.project.ledgercore.account;

import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.EntryPage;
import com.project.ledgercore.ledger.LedgerService;
import org.springframework.stereotype.Service;

import static com.project.ledgercore.account.AccountRules.requireCustomerAccount;

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

    public EntryPage entriesOf(UUID id, Long beforeSequence, int limit) {
        requireCustomerAccount(get(id));   // 404 if unknown; only customer accounts have a statement
        return ledger.entriesOf(id, beforeSequence, limit);
    }

    public List<Account> customerAccountOf(String ownerRef) {
        return accounts.findCustomerAccountsByOwner(ownerRef);
    }

    public Money balanceAt(UUID id, OffsetDateTime at) {
        requireCustomerAccount(get(id));   // only customer accounts keep a running balance
        return ledger.balanceAt(id, at).orElseThrow(() -> new AccountNotFoundException(id));
    }

}
