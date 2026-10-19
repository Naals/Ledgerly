package com.project.ledgercore.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import org.junit.jupiter.api.Test;

class NewAccountTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Test
    void customerAccountsCannotGoNegative() {
        NewAccount account = NewAccount.customer("aslan", KZT);

        assertThat(account.type()).isEqualTo(AccountType.CUSTOMER);
        assertThat(account.allowNegative()).isFalse();
    }

    @Test
    void systemAccountsMayGoNegative() {
        NewAccount account = NewAccount.system("cash-in-bank", KZT);

        assertThat(account.type()).isEqualTo(AccountType.SYSTEM);
        assertThat(account.allowNegative()).isTrue();
    }

    @Test
    void onlySystemAccountsMayAllowNegativeBalances() {
        assertThatThrownBy(() -> new NewAccount("aslan", AccountType.CUSTOMER, KZT, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only SYSTEM");
    }

    @Test
    void ownerRefMustBePresentAndFitTheColumn() {
        assertThatThrownBy(() -> NewAccount.customer(" ", KZT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NewAccount.customer("x".repeat(65), KZT)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> NewAccount.customer("x".repeat(64), KZT)).doesNotThrowAnyException();
    }
}
