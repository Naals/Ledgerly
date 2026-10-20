package com.project.ledgercore.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.LedgerService;
import com.project.ledgercore.ledger.PostingLine;
import com.project.ledgercore.ledger.PostingRequest;
import com.project.ledgercore.ledger.TransactionType;
import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class BalanceApiIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountRepository accounts;

    @Autowired
    LedgerService ledger;

    @Test
    void newAccountHasZeroBalance() throws Exception {
        Account account = accounts.create(NewAccount.customer("aslan", KZT));

        mvc.perform(get("/api/v1/accounts/{id}/balance", account.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountMinor").value(0))
                .andExpect(jsonPath("$.currency").value("KZT"));
    }

    @Test
    void balanceReflectsPostedEntries() throws Exception {
        Account funding = accounts.create(NewAccount.system("balance-it-" + UUID.randomUUID(), KZT));
        Account customer = accounts.create(NewAccount.customer("aslan", KZT));
        ledger.post(new PostingRequest(TransactionType.DEPOSIT, "test funding", List.of(
                PostingLine.debit(funding.id(), Money.of(2_500, "KZT")),
                PostingLine.credit(customer.id(), Money.of(2_500, "KZT")))));

        mvc.perform(get("/api/v1/accounts/{id}/balance", customer.id()))
                .andExpect(jsonPath("$.amountMinor").value(2500));
    }

    @Test
    void unknownAccountIsA404() throws Exception {
        mvc.perform(get("/api/v1/accounts/{id}/balance", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
