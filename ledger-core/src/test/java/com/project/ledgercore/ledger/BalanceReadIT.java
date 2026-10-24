package com.project.ledgercore.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.support.LedgerInvariants;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class BalanceReadIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    LedgerService ledger;

    @Autowired
    AccountRepository accounts;

    @Autowired
    CashService cash;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LedgerInvariants invariants;

    @Test
    void customerBalancesAreReadFromTheCache() {
        Account alice = fundedAccount();

        corruptTheCache(alice, 999);   // deliberately wrong, to prove where the value comes from
        try {
            assertThat(ledger.balanceOf(alice.id())).contains(Money.of(999, "KZT"));
        } finally {
            corruptTheCache(alice, 1_000);
        }
    }

    @Test
    void theInvariantCheckDetectsADriftingCache() {
        Account alice = fundedAccount();

        corruptTheCache(alice, 999);
        try {
            assertThatThrownBy(invariants::assertBalanceCacheMatchesEntries).isInstanceOf(AssertionError.class);
        } finally {
            corruptTheCache(alice, 1_000);
        }
    }

    private Account fundedAccount() {
        Account alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        cash.deposit(alice.id(), Money.of(1_000, "KZT"));
        return alice;
    }

    private void corruptTheCache(Account account, long balance) {
        jdbc.update("UPDATE accounts SET balance = ? WHERE id = ?", balance, account.id());
    }
}