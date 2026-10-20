package com.project.ledgercore.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class GetAccountApiIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountRepository accounts;

    @Test
    void returnsAnExistingAccount() throws Exception {
        Account account = accounts.create(NewAccount.customer("aslan", Currency.getInstance("KZT")));

        mvc.perform(get("/api/v1/accounts/{id}", account.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(account.id().toString()))
                .andExpect(jsonPath("$.ownerRef").value("aslan"))
                .andExpect(jsonPath("$.currency").value("KZT"));
    }

    @Test
    void unknownAccountIsA404Problem() throws Exception {
        mvc.perform(get("/api/v1/accounts/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:account-not-found"));
    }

    @Test
    void malformedIdIsA400Problem() throws Exception {
        mvc.perform(get("/api/v1/accounts/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
