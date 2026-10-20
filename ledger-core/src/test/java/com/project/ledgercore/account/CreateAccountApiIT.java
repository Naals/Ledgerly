package com.project.ledgercore.account;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.ledgercore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class CreateAccountApiIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void createsAnAccountAndReturnsItsLocation() throws Exception {
        mvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerRef": "aslan", "currency": "KZT"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(".*/api/v1/accounts/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.ownerRef").value("aslan"))
                .andExpect(jsonPath("$.type").value("CUSTOMER"))
                .andExpect(jsonPath("$.currency").value("KZT"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void invalidFieldsAreReportedTogetherAsAProblem() throws Exception {
        mvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerRef": " ", "currency": "kzt"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:validation-failed"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("ownerRef", "currency")));
    }

    @Test
    void unknownCurrencyIsRejected() throws Exception {
        mvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerRef": "aslan", "currency": "ZZZ"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:invalid-currency"));
    }

    @Test
    void malformedJsonIsAProblemToo() throws Exception {
        mvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
