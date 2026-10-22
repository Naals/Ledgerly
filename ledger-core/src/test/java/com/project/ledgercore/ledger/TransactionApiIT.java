package com.project.ledgercore.ledger;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.transfer.TransferService;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class TransactionApiIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountRepository accounts;

    @Autowired
    CashService cash;

    @Autowired
    TransferService transfers;

    @Test
    void returnsATransactionWithItsBalancedEntries() throws Exception {
        Account alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        Account bob = accounts.create(NewAccount.customer("bob-" + UUID.randomUUID(), KZT));
        cash.deposit(alice.id(), Money.of(1_000, "KZT"));
        UUID transactionId = transfers.transfer(alice.id(), bob.id(), Money.of(400, "KZT"));

        mvc.perform(get("/api/v1/transactions/{id}", transactionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transactionId.toString()))
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[*].amountMinor", containsInAnyOrder(-400, 400)))
                .andExpect(jsonPath("$.entries[*].accountId", containsInAnyOrder(alice.id().toString(), bob.id().toString())));
    }

    @Test
    void unknownTransactionIs404() throws Exception {
        mvc.perform(get("/api/v1/transactions/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:transaction-not-found"));
    }

    @Test
    void malformedIdIs400() throws Exception {
        mvc.perform(get("/api/v1/transactions/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }
}
