package com.project.ledgercore.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

class AccountLockerIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    AccountLocker locker;

    @Autowired
    AccountRepository accounts;

    @Autowired
    TransactionTemplate tx;

    @Test
    void locksAccountsAndReturnsThemById() {
        Account a = accounts.create(NewAccount.customer("lock-a", KZT));
        Account b = accounts.create(NewAccount.customer("lock-b", KZT));

        Map<UUID, Account> locked = tx.execute(status -> locker.lockInOrder(List.of(a.id(), b.id())));

        assertThat(locked).containsOnlyKeys(a.id(), b.id());
        assertThat(locked.get(a.id())).isEqualTo(a);
    }

    @Test
    void locksAreTakenInAscendingIdOrderWhateverOrderIsRequested() {
        Account a = accounts.create(NewAccount.customer("lock-a", KZT));
        Account b = accounts.create(NewAccount.customer("lock-b", KZT));
        Account c = accounts.create(NewAccount.customer("lock-c", KZT));
        List<UUID> requested = List.of(c.id(), a.id(), b.id(), a.id());   // unsorted, with a duplicate

        Map<UUID, Account> locked = tx.execute(status -> locker.lockInOrder(requested));

        List<String> expected = requested.stream().distinct().map(UUID::toString).sorted().toList();
        assertThat(locked.keySet().stream().map(UUID::toString).toList()).containsExactlyElementsOf(expected);
    }

    @Test
    void unknownAccountFails() {
        Account a = accounts.create(NewAccount.customer("lock-a", KZT));

        assertThatThrownBy(() -> tx.executeWithoutResult(
                status -> locker.lockInOrder(List.of(a.id(), UUID.randomUUID()))))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void refusesToRunOutsideATransaction() {
        assertThatThrownBy(() -> locker.lockInOrder(List.of(UUID.randomUUID())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
