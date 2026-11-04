package com.project.paymentservice.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.project.paymentservice.ledger.LedgerOutcome;
import com.project.paymentservice.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class TwoStepPaymentApiIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void aTwoStepPaymentIsAuthorizedFirst() throws Exception {
        mvc.perform(create("MANUAL"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.captureMode").value("MANUAL"))
                .andExpect(jsonPath("$.authorizationExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.ledgerTransactionId").isEmpty());
    }

    @Test
    void omittingTheModeMeansOneStep() throws Exception {
        mvc.perform(create(null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.captureMode").value("AUTOMATIC"))
                .andExpect(jsonPath("$.authorizationExpiresAt").isEmpty());
    }

    @Test
    void anUnknownModeIsRejected() throws Exception {
        mvc.perform(create("SOMETIMES"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:malformed-request"));
    }

    @Test
    void capturingCompletesTheAuthorizedPayment() throws Exception {
        String id = createAndReturnId("MANUAL");

        mvc.perform(post("/api/v1/payments/{id}/capture", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.ledgerTransactionId").isNotEmpty());

        mvc.perform(get("/api/v1/payments/{id}", id)).andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(post("/api/v1/payments/{id}/capture", id))   // repeating it is harmless
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void aCancelledPaymentCannotBeCaptured() throws Exception {
        String id = createAndReturnId("MANUAL");

        mvc.perform(post("/api/v1/payments/{id}/cancel", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/v1/payments/{id}/cancel", id))   // repeating it is harmless
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/payments/{id}/capture", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:payment-not-capturable"));
    }

    @Test
    void aOneStepPaymentCannotBeCapturedOrCancelled() throws Exception {
        String id = createAndReturnId(null);

        mvc.perform(post("/api/v1/payments/{id}/capture", id)).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/payments/{id}/cancel", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:payment-not-cancellable"));
    }

    @Test
    void anUnavailableLedgerIsA503AndTheCaptureCanBeRepeated() throws Exception {
        String id = createAndReturnId("MANUAL");
        ledger.captureWith(payment -> new LedgerOutcome.Unavailable<>("timeout"));

        mvc.perform(post("/api/v1/payments/{id}/capture", id))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:ledger-unavailable"));
        mvc.perform(get("/api/v1/payments/{id}", id)).andExpect(jsonPath("$.status").value("AUTHORIZED"));

        ledger.reset();
        mvc.perform(post("/api/v1/payments/{id}/capture", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void anUnknownPaymentIsNotFound() throws Exception {
        String unknown = UUID.randomUUID().toString();

        mvc.perform(post("/api/v1/payments/{id}/capture", unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:payment-not-found"));
        mvc.perform(post("/api/v1/payments/{id}/cancel", unknown)).andExpect(status().isNotFound());
    }

    private String createAndReturnId(String mode) throws Exception {
        String body = mvc.perform(create(mode)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private MockHttpServletRequestBuilder create(String mode) {
        String modeJson = mode == null ? "" : ", \"captureMode\": \"" + mode + "\"";
        return post("/api/v1/payments")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fromAccountId": "%s", "toAccountId": "%s", "amountMinor": 100000, "currency": "KZT"%s}
                        """.formatted(UUID.randomUUID(), UUID.randomUUID(), modeJson));
    }
}