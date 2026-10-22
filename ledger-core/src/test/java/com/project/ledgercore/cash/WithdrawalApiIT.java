package com.project.ledgercore.cash;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class WithdrawalApiIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountRepository accounts;

    @Autowired
    CashService cash;

    @Test
    void withdrawalDebitsTheAccount() throws Exception {
        Account alice = accounts.create(NewAccount.customer("alice", KZT));
        cash.deposit(alice.id(), Money.of(100_000, "KZT"));

        mvc.perform(withdrawal(alice.id(), 30_000))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.amountMinor").value(30000));

        mvc.perform(get("/api/v1/accounts/{id}/balance", alice.id()))
                .andExpect(jsonPath("$.amountMinor").value(70000));
    }

    @Test
    void overdraftIsA422ProblemAndChangesNothing() throws Exception {
        Account alice = accounts.create(NewAccount.customer("alice", KZT));
        cash.deposit(alice.id(), Money.of(1_000, "KZT"));

        mvc.perform(withdrawal(alice.id(), 1_001))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:insufficient-funds"));

        mvc.perform(get("/api/v1/accounts/{id}/balance", alice.id()))
                .andExpect(jsonPath("$.amountMinor").value(1000));
    }

    @Test
    void unknownAccountIs404() throws Exception {
        mvc.perform(withdrawal(UUID.randomUUID(), 100))
                .andExpect(status().isNotFound());
    }

    private static MockHttpServletRequestBuilder withdrawal(UUID accountId, long amountMinor) {
        return post("/api/v1/accounts/{id}/withdrawals", accountId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\": %d, \"currency\": \"KZT\"}".formatted(amountMinor));
    }
}
