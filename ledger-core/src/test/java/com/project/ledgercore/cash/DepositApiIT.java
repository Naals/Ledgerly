package com.project.ledgercore.cash;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class DepositApiIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountRepository accounts;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void depositCreditsTheAccount() throws Exception {
        Account alice = accounts.create(NewAccount.customer("alice", KZT));

        mvc.perform(deposit(alice.id(), 100_000, "KZT"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").isNotEmpty())
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.amountMinor").value(100000))
                .andExpect(jsonPath("$.currency").value("KZT"));

        mvc.perform(get("/api/v1/accounts/{id}/balance", alice.id()))
                .andExpect(jsonPath("$.amountMinor").value(100000));
    }

    @Test
    void unknownAccountIs404() throws Exception {
        mvc.perform(deposit(UUID.randomUUID(), 100, "KZT"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:account-not-found"));
    }

    @Test
    void frozenAccountIs409() throws Exception {
        Account frozen = accounts.create(NewAccount.customer("frozen", KZT));
        jdbc.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?", frozen.id());

        mvc.perform(deposit(frozen.id(), 100, "KZT"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:account-not-active"));
    }

    @Test
    void wrongCurrencyIs422() throws Exception {
        Account alice = accounts.create(NewAccount.customer("alice", KZT));

        mvc.perform(deposit(alice.id(), 100, "USD"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:currency-mismatch"));
    }

    @Test
    void zeroAmountIs400() throws Exception {
        Account alice = accounts.create(NewAccount.customer("alice", KZT));

        mvc.perform(deposit(alice.id(), 0, "KZT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("amountMinor"));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder deposit(
            UUID accountId, long amountMinor, String currency) {
        return post("/api/v1/accounts/{id}/deposits", accountId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\": %d, \"currency\": \"%s\"}".formatted(amountMinor, currency));
    }
}