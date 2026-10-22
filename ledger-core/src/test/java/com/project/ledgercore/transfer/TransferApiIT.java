package com.project.ledgercore.transfer;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class TransferApiIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountRepository accounts;

    @Autowired
    CashService cash;

    @Autowired
    JdbcTemplate jdbc;

    private Account alice;
    private Account bob;

    @BeforeEach
    void openAccounts() {
        alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        bob = accounts.create(NewAccount.customer("bob-" + UUID.randomUUID(), KZT));
        cash.deposit(alice.id(), Money.of(100_000, "KZT"));
    }

    @Test
    void transferMovesMoney() throws Exception {
        mvc.perform(transfer(alice.id(), bob.id(), 30_000))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").isNotEmpty())
                .andExpect(jsonPath("$.amountMinor").value(30000))
                .andExpect(jsonPath("$.currency").value("KZT"));

        mvc.perform(get("/api/v1/accounts/{id}/balance", alice.id()))
                .andExpect(jsonPath("$.amountMinor").value(70000));
        mvc.perform(get("/api/v1/accounts/{id}/balance", bob.id()))
                .andExpect(jsonPath("$.amountMinor").value(30000));
    }

    @Test
    void insufficientFundsIs422AndChangesNothing() throws Exception {
        mvc.perform(transfer(alice.id(), bob.id(), 100_001))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:insufficient-funds"));

        mvc.perform(get("/api/v1/accounts/{id}/balance", alice.id()))
                .andExpect(jsonPath("$.amountMinor").value(100000));
    }

    @Test
    void transferToTheSameAccountIs400() throws Exception {
        mvc.perform(transfer(alice.id(), alice.id(), 100))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:same-account"));
    }

    @Test
    void unknownAccountIs404() throws Exception {
        mvc.perform(transfer(alice.id(), UUID.randomUUID(), 100))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:account-not-found"));
    }

    @Test
    void frozenTargetIs409() throws Exception {
        jdbc.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?", bob.id());

        mvc.perform(transfer(alice.id(), bob.id(), 100))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:account-not-active"));
    }

    @Test
    void missingFieldsAreReportedAsValidationErrors() throws Exception {
        mvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field",
                        containsInAnyOrder("fromAccountId", "toAccountId", "amountMinor", "currency")));
    }

    private MockHttpServletRequestBuilder transfer(UUID from, UUID to, long amountMinor) {
        return post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fromAccountId": "%s", "toAccountId": "%s", "amountMinor": %d, "currency": "KZT"}
                        """.formatted(from, to, amountMinor));
    }
}