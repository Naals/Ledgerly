package com.project.ledgercore.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class AccountLifecycleApiIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountRepository accounts;

    @Autowired
    SystemAccounts systemAccounts;

    @Autowired
    CashService cash;

    @Test
    void freezingBlocksActivityAndUnfreezingRestoresIt() throws Exception {
        Account alice = newAccount();

        mvc.perform(post("/api/v1/accounts/{id}/freeze", alice.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));

        mvc.perform(post("/api/v1/accounts/{id}/deposits", alice.id())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\": 100, \"currency\": \"KZT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:account-not-active"));

        mvc.perform(post("/api/v1/accounts/{id}/unfreeze", alice.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void closingAnAccountThatHoldsMoneyIs422() throws Exception {
        Account alice = newAccount();
        cash.deposit(alice.id(), Money.of(100, "KZT"));

        mvc.perform(post("/api/v1/accounts/{id}/close", alice.id()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:account-not-empty"));
    }

    @Test
    void aClosedAccountIsFinal() throws Exception {
        Account alice = newAccount();

        mvc.perform(post("/api/v1/accounts/{id}/close", alice.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        mvc.perform(post("/api/v1/accounts/{id}/unfreeze", alice.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:invalid-status-transition"));
    }

    @Test
    void theStatusHistoryListsChangesNewestFirstWithTheirReasons() throws Exception {
        Account alice = newAccount();

        mvc.perform(post("/api/v1/accounts/{id}/freeze", alice.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"fraud check\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/accounts/{id}/unfreeze", alice.id()))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/accounts/{id}/status-history", alice.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].from").value("FROZEN"))
                .andExpect(jsonPath("$[0].to").value("ACTIVE"))
                .andExpect(jsonPath("$[1].to").value("FROZEN"))
                .andExpect(jsonPath("$[1].reason").value("fraud check"));
    }

    @Test
    void unknownAccountIs404() throws Exception {
        mvc.perform(post("/api/v1/accounts/{id}/freeze", UUID.randomUUID()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/accounts/{id}/status-history", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void theBanksOwnAccountsAreRefused() throws Exception {
        Account cashInBank = systemAccounts.cashInBank(KZT);

        mvc.perform(post("/api/v1/accounts/{id}/freeze", cashInBank.id()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:not-a-customer-account"));
    }

    private Account newAccount() {
        return accounts.create(NewAccount.customer("lifecycle-api-" + UUID.randomUUID(), KZT));
    }
}