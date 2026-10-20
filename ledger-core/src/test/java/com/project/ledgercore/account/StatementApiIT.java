package com.project.ledgercore.account;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class StatementApiIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountRepository accounts;

    @Autowired
    CashService cash;

    @Test
    void pagesThroughTheStatement() throws Exception {
        Account alice = accounts.create(NewAccount.customer("statement-api-" + UUID.randomUUID(), KZT));
        cash.deposit(alice.id(), Money.of(100, "KZT"));
        cash.deposit(alice.id(), Money.of(200, "KZT"));
        cash.deposit(alice.id(), Money.of(300, "KZT"));

        String firstPage = mvc.perform(get("/api/v1/accounts/{id}/entries?limit=2", alice.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].amountMinor").value(300))
                .andExpect(jsonPath("$.items[0].type").value("DEPOSIT"))
                .andExpect(jsonPath("$.items[1].amountMinor").value(200))
                .andReturn().getResponse().getContentAsString();

        Number cursor = JsonPath.read(firstPage, "$.nextBefore");

        mvc.perform(get("/api/v1/accounts/{id}/entries?limit=2&before={before}", alice.id(), cursor.longValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].amountMinor").value(100))
                .andExpect(jsonPath("$.nextBefore").value(nullValue()));
    }

    @Test
    void invalidLimitIsA400Problem() throws Exception {
        Account alice = accounts.create(NewAccount.customer("statement-api-limit", KZT));

        mvc.perform(get("/api/v1/accounts/{id}/entries?limit=0", alice.id()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:invalid-limit"));
    }

    @Test
    void unknownAccountIs404() throws Exception {
        mvc.perform(get("/api/v1/accounts/{id}/entries", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
