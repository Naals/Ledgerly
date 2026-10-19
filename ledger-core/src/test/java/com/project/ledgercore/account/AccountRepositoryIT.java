package com.project.ledgercore.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AccountRepositoryIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    AccountRepository accounts;

    @Test
    void createdCustomerAccountCanBeFoundById() {
        Account created = accounts.create(NewAccount.customer("aslan", KZT));

        assertThat(created.id()).isNotNull();
        assertThat(created.ownerRef()).isEqualTo("aslan");
        assertThat(created.type()).isEqualTo(AccountType.CUSTOMER);
        assertThat(created.currency()).isEqualTo(KZT);
        assertThat(created.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(created.allowNegative()).isFalse();
        assertThat(created.createdAt()).isNotNull();
        assertThat(accounts.findById(created.id())).contains(created);
    }

    @Test
    void systemAccountsMayGoNegative() {
        Account cash = accounts.create(NewAccount.system("cash-in-bank", KZT));

        assertThat(cash.type()).isEqualTo(AccountType.SYSTEM);
        assertThat(cash.allowNegative()).isTrue();
    }

    @Test
    void unknownIdIsEmpty() {
        assertThat(accounts.findById(UUID.randomUUID())).isEmpty();
    }
}
