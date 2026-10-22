package com.project.ledgercore.account;

import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.LedgerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The balance check every debit of a customer account must make (see ADR 0003):
 * lock the account row first, then call this, then post.
 */
@Service
public class DebitGuard {

    private final LedgerService ledger;

    public DebitGuard(LedgerService ledger) {
        this.ledger = ledger;
    }

    /**
     * Fails if debiting {@code amount} from {@code account} would overdraw it.
     * The caller must already hold the account's row lock, otherwise two debits could both pass this check.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireFunds(Account account, Money amount) {
        Money balance = ledger.balanceOf(account.id())
                .orElseThrow(() -> new AccountNotFoundException(account.id()));
        if (balance.minus(amount).isNegative()) {
            throw new InsufficientFundsException(account.id(), balance, amount);
        }
    }
}
