package com.project.ledgercore.transfer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class TransferReferenceIT extends AbstractIntegrationTest {

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
        cash.deposit(alice.id(), Money.of(1_000, "KZT"));
    }

    @Test
    void aTransferCarriesItsReferenceIntoTheResponseAndTheTransaction() throws Exception {
        String body = mvc.perform(transfer(UUID.randomUUID().toString(), "payment-42"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value("payment-42"))
                .andReturn().getResponse().getContentAsString();
        String transactionId = JsonPath.read(body, "$.transactionId");

        mvc.perform(get("/api/v1/transactions/{id}", transactionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value("payment-42"));
    }

    @Test
    void aTransferWithoutAReferenceStillWorks() throws Exception {
        mvc.perform(transfer(UUID.randomUUID().toString(), null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").isEmpty());
    }

    @Test
    void aBlankReferenceIsRejected() throws Exception {
        mvc.perform(transfer(UUID.randomUUID().toString(), "   "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reusingAKeyWithADifferentReferenceIsRefused() throws Exception {
        String key = UUID.randomUUID().toString();
        mvc.perform(transfer(key, "payment-1")).andExpect(status().isCreated());

        mvc.perform(transfer(key, "payment-2"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:idempotency-key-reuse"));
    }

    private MockHttpServletRequestBuilder transfer(String key, String reference) {
        String referenceJson = reference == null ? "" : ", \"reference\": \"" + reference + "\"";
        return post("/api/v1/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fromAccountId": "%s", "toAccountId": "%s", "amountMinor": 100, "currency": "KZT"%s}
                        """.formatted(alice.id(), bob.id(), referenceJson));
    }
}