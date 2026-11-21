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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class HoldCaptureRetryIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountRepository accounts;

    @Autowired
    CashService cash;

    @Autowired
    HoldService holds;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aCaptureCanRetryTheSameKeyAfterEitherAccountIsUnfrozen(boolean freezePayer) throws Exception {
        Account payer = newAccount();
        Account payee = newAccount();
        cash.deposit(payer.id(), Money.of(1_000, "KZT"));
        Hold hold = holds.place(payer.id(), payee.id(), Money.of(300, "KZT"), "capture-retry", null);
        UUID frozenAccountId = freezePayer ? payer.id() : payee.id();
        String key = UUID.randomUUID().toString();

        mvc.perform(post("/api/v1/accounts/{id}/freeze", frozenAccountId)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/holds/{id}/capture", hold.id()).header("Idempotency-Key", key))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:account-not-active"));
        mvc.perform(get("/api/v1/accounts/{id}/balance", payer.id()))
                .andExpect(jsonPath("$.amountMinor").value(1_000))
                .andExpect(jsonPath("$.heldMinor").value(300));

        mvc.perform(post("/api/v1/accounts/{id}/unfreeze", frozenAccountId)).andExpect(status().isOk());
        String captured = mvc.perform(post("/api/v1/holds/{id}/capture", hold.id())
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.capturedMinor").value(300))
                .andReturn().getResponse().getContentAsString();

        mvc.perform(post("/api/v1/holds/{id}/capture", hold.id()).header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.transactionId").value(JsonPath.<String>read(captured, "$.transactionId")));
        mvc.perform(get("/api/v1/accounts/{id}/balance", payer.id()))
                .andExpect(jsonPath("$.amountMinor").value(700))
                .andExpect(jsonPath("$.heldMinor").value(0));
        mvc.perform(get("/api/v1/accounts/{id}/balance", payee.id()))
                .andExpect(jsonPath("$.amountMinor").value(300));
    }

    private Account newAccount() {
        return accounts.create(NewAccount.customer("capture-retry-" + UUID.randomUUID(), Currency.getInstance("KZT")));
    }
}
