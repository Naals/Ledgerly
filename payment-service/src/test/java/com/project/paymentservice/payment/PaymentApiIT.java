package com.project.paymentservice.payment;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.project.paymentservice.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class PaymentApiIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    private final UUID from = UUID.randomUUID();
    private final UUID to = UUID.randomUUID();

    @Test
    void aPaymentIsCreatedAndCanBeFetched() throws Exception {
        String body = mvc.perform(payment(UUID.randomUUID().toString(), 100_000))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(".*/api/v1/payments/[0-9a-f-]{36}")))
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.amountMinor").value(100000))
                .andExpect(jsonPath("$.currency").value("KZT"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        mvc.perform(get("/api/v1/payments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void aRepeatedRequestReturnsTheSamePayment() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = mvc.perform(payment(key, 5_000)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mvc.perform(payment(key, 5_000))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value((String) JsonPath.read(first, "$.id")));
    }

    @Test
    void theKeyIsRequired() throws Exception {
        mvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:idempotency-key-required"));
    }

    @Test
    void invalidFieldsAreReportedTogether() throws Exception {
        mvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field",
                        containsInAnyOrder("fromAccountId", "toAccountId", "amountMinor", "currency")));
    }

    @Test
    void aKeyUsedForADifferentRequestIs422() throws Exception {
        String key = UUID.randomUUID().toString();
        mvc.perform(payment(key, 100)).andExpect(status().isCreated());

        mvc.perform(payment(key, 200))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:idempotency-key-reuse"));
    }

    @Test
    void anUnknownPaymentIs404() throws Exception {
        mvc.perform(get("/api/v1/payments/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:payment-not-found"));
    }

    private MockHttpServletRequestBuilder payment(String key, long amountMinor) {
        return post("/api/v1/payments")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(amountMinor));
    }

    private String json(long amountMinor) {
        return """
                {"fromAccountId": "%s", "toAccountId": "%s", "amountMinor": %d, "currency": "KZT"}
                """.formatted(from, to, amountMinor);
    }
}