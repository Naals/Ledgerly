package com.project.ledgercore.account;

import static com.project.ledgercore.account.AccountRules.requireCustomerAccount;

import com.project.ledgercore.common.BadRequestException;
import com.project.ledgercore.common.BusinessRuleException;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.LedgerService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Freezing, unfreezing and closing customer accounts, with a history of every change. */
@Service
public class AccountLifecycleService {

    static final int MAX_REASON_LENGTH = 255;
    private static final int HISTORY_LIMIT = 100;

    private final AccountRepository accounts;
    private final LedgerService ledger;
    private final AccountStatusHistoryRepository history;

    public AccountLifecycleService(AccountRepository accounts, LedgerService ledger,
                                   AccountStatusHistoryRepository history) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.history = history;
    }

    public Account freeze(UUID accountId) {
        return freeze(accountId, null);
    }

    public Account freeze(UUID accountId, String reason) {
        return changeStatus(accountId, AccountStatus.FROZEN, reason);
    }

    public Account unfreeze(UUID accountId) {
        return unfreeze(accountId, null);
    }

    public Account unfreeze(UUID accountId, String reason) {
        return changeStatus(accountId, AccountStatus.ACTIVE, reason);
    }

    public Account close(UUID accountId) {
        return close(accountId, null);
    }

    public Account close(UUID accountId, String reason) {
        return changeStatus(accountId, AccountStatus.CLOSED, reason);
    }

    public Account changeStatus(UUID accountId, AccountStatus target) {
        return changeStatus(accountId, target, null);
    }

    /**
     * Moves the account to the target status and records the change. The account row is locked first,
     * so the change cannot interleave with a debit, and the balance seen when closing is the real one.
     * Asking for the status the account already has changes nothing and records nothing.
     */
    @Transactional
    public Account changeStatus(UUID accountId, AccountStatus target, String reason) {
        String cleanReason = cleanReason(reason);
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
        history.record(account.id(), account.status(), target, cleanReason);
        return accounts.findById(account.id()).orElseThrow();
    }

    /** The most recent status changes of an account, newest first. */
    @Transactional(readOnly = true)
    public List<AccountStatusEvent> history(UUID accountId) {
        accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
        return history.findRecent(accountId, HISTORY_LIMIT);
    }

    private void requireEmpty(Account account) {
        Money balance = ledger.balanceOf(account.id()).orElseThrow(() -> new AccountNotFoundException(account.id()));
        if (!balance.isZero()) {
            throw new BusinessRuleException("account-not-empty",
                    "Account " + account.id() + " still holds " + balance + " and cannot be closed");
        }
    }

    private static String cleanReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String trimmed = reason.strip();
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw new BadRequestException("invalid-reason", "reason must be at most " + MAX_REASON_LENGTH + " characters");
        }
        return trimmed;
    }
}