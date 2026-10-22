package com.project.ledgercore.cash;

import com.project.ledgercore.account.*;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.LedgerService;
import com.project.ledgercore.ledger.PostingLine;
import com.project.ledgercore.ledger.PostingRequest;
import com.project.ledgercore.ledger.TransactionType;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.project.ledgercore.account.AccountRules.*;

/** Cash in and cash out against the bank's own cash account. */
@Service
public class CashService {

    private final AccountRepository accounts;
    private final SystemAccounts systemAccounts;
    private final DebitGuard debitGuard;
    private final LedgerService ledger;

    public CashService(AccountRepository accounts, SystemAccounts systemAccounts,
                       DebitGuard debitGuard, LedgerService ledger) {
        this.accounts = accounts;
        this.systemAccounts = systemAccounts;
        this.debitGuard = debitGuard;
        this.ledger = ledger;
    }

    /** Cash in: debit the bank's cash account, credit the customer. Returns the ledger transaction id. */
    @Transactional
    public UUID deposit(UUID accountId, Money amount) {
        requirePositive(amount);
        Account account = accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
        requireCustomerAccount(account);
        requireUsable(account, amount);

        Account cash = systemAccounts.cashInBank(amount.currency());
        return ledger.post(new PostingRequest(TransactionType.DEPOSIT, "Cash deposit", List.of(
                PostingLine.debit(cash.id(), amount),
                PostingLine.credit(account.id(), amount))));
    }

    /**
     * Cash out: debit the customer, credit the bank's cash account.
     * The account row is locked first, so concurrent withdrawals queue up and each one
     * sees the balance left by the previous one. A customer account can never go negative.
     */
    @Transactional
    public UUID withdraw(UUID accountId, Money amount) {
        requirePositive(amount);
        Account account = accounts.findAndLockById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        requireCustomerAccount(account);
        requireUsable(account, amount);
        debitGuard.requireFunds(account, amount);

        Account cash = systemAccounts.cashInBank(amount.currency());
        return ledger.post(new PostingRequest(TransactionType.WITHDRAWAL, "Cash withdrawal", List.of(
                PostingLine.debit(account.id(), amount),
                PostingLine.credit(cash.id(), amount))));
    }
}
