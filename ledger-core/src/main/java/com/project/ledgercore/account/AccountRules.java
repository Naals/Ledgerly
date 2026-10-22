package com.project.ledgercore.account;

import com.project.ledgercore.common.BadRequestException;
import com.project.ledgercore.common.BusinessRuleException;
import com.project.ledgercore.common.Money;

/** Rules shared by every operation that moves money on an account. */
public final class AccountRules {

    private AccountRules() {
    }

    /** The amount of a cash or transfer operation must be strictly positive. */
    public static void requirePositive(Money amount) {
        if (!amount.isPositive()) {
            throw new BadRequestException("invalid-amount", "Amount must be positive, was " + amount);
        }
    }

    /** The account must be ACTIVE and held in the currency of the amount. */
    public static void requireUsable(Account account, Money amount) {
        if (account.status() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException(account.id(), account.status());
        }
        if (!account.currency().equals(amount.currency())) {
            throw new CurrencyMismatchException(account.id(), account.currency(), amount.currency());
        }
    }

    /** Customer-facing operations may only touch customer accounts, never the bank's own. */
    public static void requireCustomerAccount(Account account) {
        if (account.type() != AccountType.CUSTOMER) {
            throw new BusinessRuleException(
                    "not-a-customer-account", "Account " + account.id() + " is not a customer account");
        }
    }
}
