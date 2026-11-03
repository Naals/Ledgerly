package com.project.ledgercore.account;

import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.LedgerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The funds check every debit of a customer account must make (see ADR 0003): lock the account row first,
 * then call this, then post. It checks the AVAILABLE balance (balance minus holds), because held funds are
 * promised to someone else (ADR 0014).
 */
@Service
public class DebitGuard {

    private final LedgerService ledger;

    public DebitGuard(LedgerService ledger) {
        this.ledger = ledger;
    }

    /**
     * Fails if debiting {@code amount} from {@code account} would spend funds that are held, or overdraw it.
     * The caller must already hold the account's row lock, otherwise two debits could both pass this check.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireFunds(Account account, Money amount) {
        Money available = ledger.balanceSummaryOf(account.id())
                .orElseThrow(() -> new AccountNotFoundException(account.id()))
                .available();
        if (available.minus(amount).isNegative()) {
            throw new InsufficientFundsException(account.id(), available, amount);
        }
    }
}