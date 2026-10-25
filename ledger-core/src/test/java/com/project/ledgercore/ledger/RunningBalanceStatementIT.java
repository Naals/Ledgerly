package com.project.ledgercore.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.AccountService;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.account.SystemAccounts;
import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.BusinessRuleException;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class RunningBalanceStatementIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    LedgerService ledger;

    @Autowired
    AccountService accountService;

    @Autowired
    AccountRepository accounts;

    @Autowired
    SystemAccounts systemAccounts;

    @Autowired
    CashService cash;

    @Autowired
    MockMvc mvc;

    @Test
    void theStatementNumbersEntriesAndShowsTheRunningBalance() {
        Account alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        cash.deposit(alice.id(), Money.of(1_000, "KZT"));
        cash.deposit(alice.id(), Money.of(500, "KZT"));
        cash.withdraw(alice.id(), Money.of(300, "KZT"));

        List<EntryView> statement = ledger.entriesOf(alice.id(), null, 10).items();

        assertThat(statement).extracting(EntryView::sequence).containsExactly(3L, 2L, 1L);
        assertThat(statement).extracting(entry -> entry.amount().minorUnits()).containsExactly(-300L, 500L, 1_000L);
        assertThat(statement).extracting(entry -> entry.balanceAfter().minorUnits())
                .containsExactly(1_200L, 1_500L, 1_000L);
    }

    @Test
    void aBankOwnedAccountHasNoStatement() {
        Account cashInBank = systemAccounts.cashInBank(KZT);

        assertThatThrownBy(() -> accountService.entriesOf(cashInBank.id(), null, 10))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not a customer account");
    }

    @Test
    void theApiExposesTheEntryNumberAndTheRunningBalance() throws Exception {
        Account alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        cash.deposit(alice.id(), Money.of(1_000, "KZT"));
        cash.deposit(alice.id(), Money.of(500, "KZT"));

        mvc.perform(get("/api/v1/accounts/{id}/entries", alice.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].sequence").value(2))
                .andExpect(jsonPath("$.items[0].balanceAfterMinor").value(1500))
                .andExpect(jsonPath("$.items[1].sequence").value(1))
                .andExpect(jsonPath("$.items[1].balanceAfterMinor").value(1000))
                .andExpect(jsonPath("$.items[0].postedAt").isNotEmpty());
    }
}