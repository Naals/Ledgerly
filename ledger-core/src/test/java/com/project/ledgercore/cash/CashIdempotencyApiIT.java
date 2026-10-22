package com.project.ledgercore.cash;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

class CashIdempotencyApiIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountRepository accounts;

    @Autowired
    CashService cash;

    @Test
    void retriedDepositCreditsOnce() throws Exception {
        Account alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        String key = UUID.randomUUID().toString();

        mvc.perform(cashRequest("deposits", alice.id(), key, 50_000))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"));
        mvc.perform(cashRequest("deposits", alice.id(), key, 50_000))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        mvc.perform(get("/api/v1/accounts/{id}/balance", alice.id()))
                .andExpect(jsonPath("$.amountMinor").value(50000));
    }

    @Test
    void retriedWithdrawalDebitsOnce() throws Exception {
        Account alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        cash.deposit(alice.id(), Money.of(100_000, "KZT"));
        String key = UUID.randomUUID().toString();

        mvc.perform(cashRequest("withdrawals", alice.id(), key, 30_000)).andExpect(status().isCreated());
        mvc.perform(cashRequest("withdrawals", alice.id(), key, 30_000))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        mvc.perform(get("/api/v1/accounts/{id}/balance", alice.id()))
                .andExpect(jsonPath("$.amountMinor").value(70000));
    }

    @Test
    void theSameKeyForADepositAndAWithdrawalAreIndependent() throws Exception {
        Account alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        String key = UUID.randomUUID().toString();

        mvc.perform(cashRequest("deposits", alice.id(), key, 10_000))
                .andExpect(header().string("Idempotent-Replayed", "false"));
        mvc.perform(cashRequest("withdrawals", alice.id(), key, 4_000))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"));

        mvc.perform(get("/api/v1/accounts/{id}/balance", alice.id()))
                .andExpect(jsonPath("$.amountMinor").value(6000));
    }

    @Test
    void depositWithoutAKeyIs400() throws Exception {
        Account alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));

        mvc.perform(post("/api/v1/accounts/{id}/deposits", alice.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\": 100, \"currency\": \"KZT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:idempotency-key-required"));
    }

    private static MockHttpServletRequestBuilder cashRequest(String path, UUID accountId, String key, long amountMinor) {
        return post("/api/v1/accounts/{id}/" + path, accountId)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\": %d, \"currency\": \"KZT\"}".formatted(amountMinor));
    }
}
