package com.project.ledgercore.account;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.common.BadRequestException;
import com.project.ledgercore.common.BusinessRuleException;
import com.project.ledgercore.common.Money;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountRulesTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Test
    void amountMustBePositive() {
        assertThatCode(() -> AccountRules.requirePositive(Money.of(1, "KZT"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> AccountRules.requirePositive(Money.zero(KZT)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> AccountRules.requirePositive(Money.of(-5, "KZT")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void activeAccountInTheRightCurrencyIsUsable() {
        assertThatCode(() -> AccountRules.requireUsable(account(AccountStatus.ACTIVE), Money.of(100, "KZT")))
                .doesNotThrowAnyException();
    }

    @Test
    void frozenAndClosedAccountsAreNotUsable() {
        assertThatThrownBy(() -> AccountRules.requireUsable(account(AccountStatus.FROZEN), Money.of(100, "KZT")))
                .isInstanceOf(AccountNotActiveException.class);
        assertThatThrownBy(() -> AccountRules.requireUsable(account(AccountStatus.CLOSED), Money.of(100, "KZT")))
                .isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    void currencyMustMatchTheAccount() {
        assertThatThrownBy(() -> AccountRules.requireUsable(account(AccountStatus.ACTIVE), Money.of(100, "USD")))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void onlyCustomerAccountsMayBeUsedByCustomerOperations() {
        assertThatCode(() -> AccountRules.requireCustomerAccount(account(AccountStatus.ACTIVE)))
                .doesNotThrowAnyException();

        Account bankOwned = new Account(UUID.randomUUID(), "system:cash-in-bank", AccountType.SYSTEM, KZT,
                AccountStatus.ACTIVE, true, OffsetDateTime.now());
        assertThatThrownBy(() -> AccountRules.requireCustomerAccount(bankOwned))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not a customer account");
    }

    private static Account account(AccountStatus status) {
        return new Account(UUID.randomUUID(), "owner", AccountType.CUSTOMER, KZT, status, false, OffsetDateTime.now());
    }
}
