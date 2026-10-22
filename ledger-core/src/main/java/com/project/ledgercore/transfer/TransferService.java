package com.project.ledgercore.transfer;

import static com.project.ledgercore.account.AccountRules.requireCustomerAccount;
import static com.project.ledgercore.account.AccountRules.requirePositive;
import static com.project.ledgercore.account.AccountRules.requireUsable;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountLocker;
import com.project.ledgercore.account.DebitGuard;
import com.project.ledgercore.common.BadRequestException;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.LedgerService;
import com.project.ledgercore.ledger.PostingLine;
import com.project.ledgercore.ledger.PostingRequest;
import com.project.ledgercore.ledger.TransactionType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private final AccountLocker locker;
    private final DebitGuard debitGuard;
    private final LedgerService ledger;

    public TransferService(AccountLocker locker, DebitGuard debitGuard, LedgerService ledger) {
        this.locker = locker;
        this.debitGuard = debitGuard;
        this.ledger = ledger;
    }

    /**
     * Moves money between two customer accounts as one balanced ledger transaction.
     * Both accounts are locked in a fixed order (no deadlocks), then the source balance is checked
     * (no overdraft), then the posting is written. Returns the ledger transaction id.
     */
    @Transactional
    public UUID transfer(UUID fromAccountId, UUID toAccountId, Money amount) {
        requirePositive(amount);
        if (fromAccountId.equals(toAccountId)) {
            throw new BadRequestException("same-account", "Cannot transfer from an account to itself");
        }

        Map<UUID, Account> locked = locker.lockInOrder(List.of(fromAccountId, toAccountId));
        Account from = locked.get(fromAccountId);
        Account to = locked.get(toAccountId);
        for (Account account : List.of(from, to)) {
            requireCustomerAccount(account);
            requireUsable(account, amount);
        }
        debitGuard.requireFunds(from, amount);

        return ledger.post(new PostingRequest(TransactionType.TRANSFER, "Transfer", List.of(
                PostingLine.debit(from.id(), amount),
                PostingLine.credit(to.id(), amount))));
    }
}