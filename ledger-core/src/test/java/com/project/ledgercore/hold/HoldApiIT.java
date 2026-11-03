package com.project.ledgercore.hold;

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
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class HoldApiIT extends AbstractIntegrationTest {

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
    void placingAHoldReservesTheMoney() throws Exception {
        mvc.perform(place(UUID.randomUUID().toString(), 300))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.amountMinor").value(300))
                .andExpect(jsonPath("$.reference").value("payment-42"));

        mvc.perform(get("/api/v1/accounts/{id}/balance", alice.id()))
                .andExpect(jsonPath("$.amountMinor").value(1_000))
                .andExpect(jsonPath("$.heldMinor").value(300))
                .andExpect(jsonPath("$.availableMinor").value(700));
    }

    @Test
    void aHoldBeyondTheAvailableBalanceIsRefused() throws Exception {
        mvc.perform(place(UUID.randomUUID().toString(), 1_001))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:insufficient-funds"));
    }

    @Test
    void retryingWithTheSameKeyReturnsTheSameHoldAndReservesOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = mvc.perform(place(key, 300)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mvc.perform(place(key, 300))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(JsonPath.<String>read(first, "$.id")));

        mvc.perform(get("/api/v1/accounts/{id}/balance", alice.id()))
                .andExpect(jsonPath("$.heldMinor").value(300));
    }

    @Test
    void capturingMovesTheMoney() throws Exception {
        String holdId = placeAndReturnId(300);

        mvc.perform(capture(holdId, UUID.randomUUID().toString(), null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capturedMinor").value(300))
                .andExpect(jsonPath("$.transactionId").isNotEmpty());

        mvc.perform(get("/api/v1/holds/{id}", holdId))
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.ledgerTransactionId").isNotEmpty());
        mvc.perform(get("/api/v1/accounts/{id}/balance", alice.id()))
                .andExpect(jsonPath("$.amountMinor").value(700))
                .andExpect(jsonPath("$.heldMinor").value(0));
        mvc.perform(get("/api/v1/accounts/{id}/balance", bob.id()))
                .andExpect(jsonPath("$.amountMinor").value(300));
    }

    @Test
    void aPartialCaptureFreesTheRest() throws Exception {
        String holdId = placeAndReturnId(300);

        mvc.perform(capture(holdId, UUID.randomUUID().toString(), "{\"amountMinor\": 120}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capturedMinor").value(120));

        mvc.perform(get("/api/v1/accounts/{id}/balance", alice.id()))
                .andExpect(jsonPath("$.amountMinor").value(880))
                .andExpect(jsonPath("$.availableMinor").value(880));
    }

    @Test
    void capturingAgainWithANewKeyIsAConflict() throws Exception {
        String holdId = placeAndReturnId(300);
        mvc.perform(capture(holdId, UUID.randomUUID().toString(), null)).andExpect(status().isCreated());

        mvc.perform(capture(holdId, UUID.randomUUID().toString(), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:hold-not-active"));
    }

    @Test
    void releasingFreesTheFundsAndMayBeRepeated() throws Exception {
        String holdId = placeAndReturnId(300);

        mvc.perform(post("/api/v1/holds/{id}/release", holdId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
        mvc.perform(post("/api/v1/holds/{id}/release", holdId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));

        mvc.perform(get("/api/v1/accounts/{id}/balance", alice.id()))
                .andExpect(jsonPath("$.heldMinor").value(0))
                .andExpect(jsonPath("$.availableMinor").value(1_000));
    }

    @Test
    void anUnknownHoldIsNotFound() throws Exception {
        String unknown = UUID.randomUUID().toString();

        mvc.perform(get("/api/v1/holds/{id}", unknown)).andExpect(status().isNotFound());
        mvc.perform(capture(unknown, UUID.randomUUID().toString(), null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:hold-not-found"));
    }

    private String placeAndReturnId(long amount) throws Exception {
        String body = mvc.perform(place(UUID.randomUUID().toString(), amount)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private MockHttpServletRequestBuilder place(String key, long amount) {
        return post("/api/v1/accounts/{id}/holds", alice.id())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"toAccountId": "%s", "amountMinor": %d, "currency": "KZT", "reference": "payment-42"}
                        """.formatted(bob.id(), amount));
    }

    private MockHttpServletRequestBuilder capture(String holdId, String key, String body) {
        MockHttpServletRequestBuilder request = post("/api/v1/holds/{id}/capture", holdId)
                .header("Idempotency-Key", key);
        return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}