package com.project.ledgercore.cash;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountNotActiveException;
import com.project.ledgercore.account.AccountNotFoundException;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.AccountStatus;
import com.project.ledgercore.account.CurrencyMismatchException;
import com.project.ledgercore.account.SystemAccounts;
import com.project.ledgercore.common.BadRequestException;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.LedgerService;
import com.project.ledgercore.ledger.PostingLine;
import com.project.ledgercore.ledger.PostingRequest;
import com.project.ledgercore.ledger.TransactionType;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cash in and cash out against the bank's own cash account. */
@Service
public class CashService {

    private final AccountRepository accounts;
    private final SystemAccounts systemAccounts;
    private final LedgerService ledger;

    public CashService(AccountRepository accounts, SystemAccounts systemAccounts, LedgerService ledger) {
        this.accounts = accounts;
        this.systemAccounts = systemAccounts;
        this.ledger = ledger;
    }

    /** Cash in: debit the bank's cash account, credit the customer. Returns the ledger transaction id. */
    @Transactional
    public UUID deposit(UUID accountId, Money amount) {
        requirePositive(amount);
        Account account = accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
        requireUsable(account, amount);

        Account cash = systemAccounts.cashInBank(amount.currency());
        return ledger.post(new PostingRequest(TransactionType.DEPOSIT, "Cash deposit", List.of(
                PostingLine.debit(cash.id(), amount),
                PostingLine.credit(account.id(), amount))));
    }

    private static void requirePositive(Money amount) {
        if (!amount.isPositive()) {
            throw new BadRequestException("invalid-amount", "Amount must be positive, was " + amount);
        }
    }

    private static void requireUsable(Account account, Money amount) {
        if (account.status() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException(account.id(), account.status());
        }
        if (!account.currency().equals(amount.currency())) {
            throw new CurrencyMismatchException(account.id(), account.currency(), amount.currency());
        }
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
        requireUsable(account, amount);

        Money balance = ledger.balanceOf(account.id()).orElseThrow(() -> new AccountNotFoundException(accountId));
        if (balance.minus(amount).isNegative()) {
            throw new InsufficientFundsException(account.id(), balance, amount);
        }

        Account cash = systemAccounts.cashInBank(amount.currency());
        return ledger.post(new PostingRequest(TransactionType.WITHDRAWAL, "Cash withdrawal", List.of(
                PostingLine.debit(account.id(), amount),
                PostingLine.credit(cash.id(), amount))));
    }
}
