package com.project.ledgercore.hold;

import static com.project.ledgercore.account.AccountRules.requireCustomerAccount;
import static com.project.ledgercore.account.AccountRules.requirePositive;
import static com.project.ledgercore.account.AccountRules.requireUsable;

import com.project.ledgercore.account.AccountLocker;
import com.project.ledgercore.ledger.LedgerService;
import com.project.ledgercore.ledger.PostingLine;
import com.project.ledgercore.ledger.PostingRequest;
import com.project.ledgercore.ledger.TransactionType;
import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountNotFoundException;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.DebitGuard;
import com.project.ledgercore.common.BadRequestException;
import com.project.ledgercore.common.Money;
import java.time.Duration;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holds: reserve part of a customer's balance for a payment, then capture it or let it go.
 *
 * <p>Lock order, everywhere: the hold's row first, then account rows in ascending id (ADR 0014). A flow that
 * took them the other way round could deadlock with one that takes them this way.
 */
@Service
public class HoldService {

    private final HoldRepository holds;
    private final AccountRepository accounts;
    private final DebitGuard debitGuard;
    private final AccountLocker locker;
    private final LedgerService ledger;

    public HoldService(HoldRepository holds, AccountRepository accounts, DebitGuard debitGuard,
                       AccountLocker locker, LedgerService ledger) {
        this.holds = holds;
        this.accounts = accounts;
        this.debitGuard = debitGuard;
        this.locker = locker;
        this.ledger = ledger;
    }

    /**
     * Moves the held money to the payee as an ordinary transfer, labelled with the hold's reference. The whole hold
     * is freed; a partial capture takes only {@code requestedMinor} of it and the rest is available again.
     *
     * <p>The order matters (ADR 0014): the hold is marked captured BEFORE the debit is posted. While it was still
     * active its amount was reserved, and the database would refuse the debit.
     */
    @Transactional
    public Capture capture(UUID holdId, Long requestedMinor) {
        Hold hold = holds.findByIdForUpdate(holdId).orElseThrow(() -> new HoldNotFoundException(holdId));
        if (hold.status() != HoldStatus.ACTIVE) {
            throw new HoldNotActiveException(holdId, hold.status());
        }
        if (hold.expired()) {
            throw new HoldExpiredException(holdId);
        }
        Money captured = HoldRules.resolveCaptureAmount(hold.amount(), requestedMinor);

        Map<UUID, Account> locked = locker.lockInOrder(List.of(hold.accountId(), hold.toAccountId()));
        Account payer = locked.get(hold.accountId());
        Account payee = locked.get(hold.toAccountId());
        for (Account account : List.of(payer, payee)) {
            requireCustomerAccount(account);
            requireUsable(account, captured);
        }

        holds.markCaptured(holdId, captured.minorUnits());
        UUID transactionId = ledger.post(new PostingRequest(
                TransactionType.TRANSFER,
                "Capture of hold " + holdId,
                List.of(PostingLine.debit(payer.id(), captured), PostingLine.credit(payee.id(), captured)),
                null,
                hold.reference()));
        holds.setLedgerTransaction(holdId, transactionId);
        return new Capture(holdId, transactionId, captured);
    }

    /**
     * Reserves {@code amount} of the payer's available balance for a payment to {@code toAccountId}.
     * The payer's row is locked and its available balance checked, so two holds cannot both claim the same money;
     * the database would refuse the second one anyway (V15).
     */
    @Transactional
    public Hold place(UUID accountId, UUID toAccountId, Money amount, String reference, Long ttlSeconds) {
        requirePositive(amount);
        Duration ttl = HoldRules.resolveTtl(ttlSeconds);
        if (accountId.equals(toAccountId)) {
            throw new BadRequestException("same-account", "A hold needs two different accounts");
        }

        Account payee = accounts.findById(toAccountId).orElseThrow(() -> new AccountNotFoundException(toAccountId));
        requireCustomerAccount(payee);
        requireUsable(payee, amount);

        Account payer = accounts.findAndLockById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
        requireCustomerAccount(payer);
        requireUsable(payer, amount);
        debitGuard.requireFunds(payer, amount);

        return holds.insert(accountId, toAccountId, amount, reference, ttl);
    }

    /** Cancels an ACTIVE hold. Releasing a hold that was already released or expired changes nothing. */
    @Transactional
    public Hold release(UUID holdId) {
        Hold hold = holds.findByIdForUpdate(holdId).orElseThrow(() -> new HoldNotFoundException(holdId));
        return switch (hold.status()) {
            case ACTIVE -> {
                holds.finish(holdId, HoldStatus.RELEASED);
                yield holds.findById(holdId).orElseThrow(() -> new HoldNotFoundException(holdId));
            }
            case RELEASED, EXPIRED -> hold;
            case CAPTURED -> throw new HoldNotActiveException(holdId, hold.status());
        };
    }

    @Transactional(readOnly = true)
    public Hold get(UUID holdId) {
        return holds.findById(holdId).orElseThrow(() -> new HoldNotFoundException(holdId));
    }
}