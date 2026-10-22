package com.project.ledgercore.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.support.ConcurrentRunner;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class TransferIdempotencyApiIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountRepository accounts;

    @Autowired
    CashService cash;

    private Account alice;
    private Account bob;

    @BeforeEach
    void openAccounts() {
        alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        bob = accounts.create(NewAccount.customer("bob-" + UUID.randomUUID(), KZT));
        cash.deposit(alice.id(), Money.of(100_000, "KZT"));
    }

    @Test
    void retryWithTheSameKeyMovesMoneyOnce() throws Exception {
        String key = UUID.randomUUID().toString();

        String first = mvc.perform(transfer(key, 30_000))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(transfer(key, 30_000))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(second, "$.transactionId"))
                .isEqualTo(JsonPath.<String>read(first, "$.transactionId"));
        assertBalances(70_000, 30_000);
    }

    @Test
    void missingKeyIs400() throws Exception {
        mvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:idempotency-key-required"));
    }

    @Test
    void malformedKeyIs400() throws Exception {
        mvc.perform(transfer("short", 100))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:invalid-idempotency-key"));
    }

    @Test
    void sameKeyWithADifferentAmountIs422AndMovesNothingMore() throws Exception {
        String key = UUID.randomUUID().toString();
        mvc.perform(transfer(key, 30_000)).andExpect(status().isCreated());

        mvc.perform(transfer(key, 40_000))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:idempotency-key-reuse"));

        assertBalances(70_000, 30_000);
    }

    @Test
    void concurrentRetriesMoveMoneyOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        List<Callable<String>> requests = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            requests.add(() -> {
                String body = mvc.perform(transfer(key, 30_000))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString();
                return JsonPath.read(body, "$.transactionId");
            });
        }

        List<String> transactionIds = ConcurrentRunner.runAll(8, requests);

        assertThat(transactionIds).hasSize(10).containsOnly(transactionIds.get(0));
        assertBalances(70_000, 30_000);
    }

    @Test
    void failedTransferCanBeRetriedWithTheSameKey() throws Exception {
        String key = UUID.randomUUID().toString();
        mvc.perform(transfer(key, 200_000))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:insufficient-funds"));

        cash.deposit(alice.id(), Money.of(200_000, "KZT"));

        mvc.perform(transfer(key, 200_000))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"));
    }

    private void assertBalances(long aliceMinor, long bobMinor) throws Exception {
        mvc.perform(get("/api/v1/accounts/{id}/balance", alice.id()))
                .andExpect(jsonPath("$.amountMinor").value(aliceMinor));
        mvc.perform(get("/api/v1/accounts/{id}/balance", bob.id()))
                .andExpect(jsonPath("$.amountMinor").value(bobMinor));
    }

    private MockHttpServletRequestBuilder transfer(String key, long amountMinor) {
        return post("/api/v1/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(amountMinor));
    }

    private String body(long amountMinor) {
        return """
                {"fromAccountId": "%s", "toAccountId": "%s", "amountMinor": %d, "currency": "KZT"}
                """.formatted(alice.id(), bob.id(), amountMinor);
    }
}