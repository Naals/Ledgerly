package com.project.ledgercore.account;

import static com.project.ledgercore.account.AccountRules.requireCustomerAccount;

import com.project.ledgercore.common.BusinessRuleException;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.LedgerService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Freezing, unfreezing and closing customer accounts. */
@Service
public class AccountLifecycleService {

    private final AccountRepository accounts;
    private final LedgerService ledger;

    public AccountLifecycleService(AccountRepository accounts, LedgerService ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    public Account freeze(UUID accountId) {
        return changeStatus(accountId, AccountStatus.FROZEN);
    }

    public Account unfreeze(UUID accountId) {
        return changeStatus(accountId, AccountStatus.ACTIVE);
    }

    public Account close(UUID accountId) {
        return changeStatus(accountId, AccountStatus.CLOSED);
    }

    /**
     * Moves the account to the target status. The account row is locked first, so the change cannot
     * interleave with a debit, and the balance seen when closing is the real one. Asking for the status
     * the account already has changes nothing.
     */
    @Transactional
    public Account changeStatus(UUID accountId, AccountStatus target) {
        Account account = accounts.findAndLockById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        requireCustomerAccount(account);
        AccountLifecycle.requireAllowed(account.id(), account.status(), target);
        if (account.status() == target) {
            return account;
        }
        if (target == AccountStatus.CLOSED) {
            requireEmpty(account);
        }

        accounts.updateStatus(account.id(), target);
        return accounts.findById(account.id()).orElseThrow();
    }

    private void requireEmpty(Account account) {
        Money balance = ledger.balanceOf(account.id()).orElseThrow(() -> new AccountNotFoundException(account.id()));
        if (!balance.isZero()) {
            throw new BusinessRuleException("account-not-empty",
                    "Account " + account.id() + " still holds " + balance + " and cannot be closed");
        }
    }
}