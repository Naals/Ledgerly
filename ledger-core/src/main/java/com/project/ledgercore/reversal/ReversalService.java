package com.project.ledgercore.reversal;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountLocker;
import com.project.ledgercore.account.AccountNotActiveException;
import com.project.ledgercore.account.AccountStatus;
import com.project.ledgercore.account.AccountType;
import com.project.ledgercore.account.DebitGuard;
import com.project.ledgercore.common.BadRequestException;
import com.project.ledgercore.ledger.LedgerService;
import com.project.ledgercore.ledger.PostingLine;
import com.project.ledgercore.ledger.PostingRequest;
import com.project.ledgercore.ledger.TransactionEntryView;
import com.project.ledgercore.ledger.TransactionType;
import com.project.ledgercore.ledger.TransactionView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Corrects a transaction by posting its exact opposite as a new transaction. */
@Service
public class ReversalService {

    static final int MAX_REASON_LENGTH = 200;

    private final LedgerService ledger;
    private final AccountLocker locker;
    private final DebitGuard debitGuard;

    public ReversalService(LedgerService ledger, AccountLocker locker, DebitGuard debitGuard) {
        this.ledger = ledger;
        this.locker = locker;
        this.debitGuard = debitGuard;
    }

    /** Returns the id of the new REVERSAL transaction. A transaction can be reversed once. */
    @Transactional
    public UUID reverse(UUID transactionId, String reason) {
        String cleanReason = cleanReason(reason);
        TransactionView original = ledger.transaction(transactionId);
        if (original.type() == TransactionType.REVERSAL) {
            throw new NotReversibleException(transactionId);
        }

        // Lock every account the transaction touched, in the global order, before looking at anything else:
        // the checks below then describe the state the reversal will really see, and a concurrent reversal
        // of the same transaction waits here and then finds this one.
        Map<UUID, Account> locked = locker.lockInOrder(
                original.entries().stream().map(TransactionEntryView::accountId).toList());

        ledger.reversalOf(transactionId).ifPresent(existing -> {
            throw new AlreadyReversedException(transactionId, existing);
        });

        for (TransactionEntryView entry : original.entries()) {
            Account account = locked.get(entry.accountId());
            if (account.type() != AccountType.CUSTOMER) {
                continue;   // the bank's own accounts have no lifecycle and may go negative
            }
            if (account.status() != AccountStatus.ACTIVE) {
                throw new AccountNotActiveException(account.id(), account.status());
            }
            if (entry.amount().isPositive()) {
                debitGuard.requireFunds(account, entry.amount());   // the reversal takes this money back
            }
        }

        List<PostingLine> opposite = original.entries().stream()
                .map(entry -> new PostingLine(entry.accountId(), entry.amount().negate()))
                .toList();
        return ledger.post(new PostingRequest(
                TransactionType.REVERSAL, description(transactionId, cleanReason), opposite, transactionId));
    }

    private static String description(UUID transactionId, String reason) {
        return reason == null ? "Reversal of " + transactionId : "Reversal of " + transactionId + ": " + reason;
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