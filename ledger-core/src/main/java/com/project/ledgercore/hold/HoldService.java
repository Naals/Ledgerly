package com.project.ledgercore.hold;

import static com.project.ledgercore.account.AccountRules.requireCustomerAccount;
import static com.project.ledgercore.account.AccountRules.requirePositive;
import static com.project.ledgercore.account.AccountRules.requireUsable;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountNotFoundException;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.DebitGuard;
import com.project.ledgercore.common.BadRequestException;
import com.project.ledgercore.common.Money;
import java.time.Duration;
import java.util.UUID;
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

    public HoldService(HoldRepository holds, AccountRepository accounts, DebitGuard debitGuard) {
        this.holds = holds;
        this.accounts = accounts;
        this.debitGuard = debitGuard;
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