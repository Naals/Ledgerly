package com.project.paymentservice.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.project.paymentservice.payment.Payment;
import com.project.paymentservice.payment.PaymentStatus;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class LedgerClientTest {

    private final MockRestServiceServer server;
    private final LedgerClient client;

    LedgerClientTest() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ledger");
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.client = new LedgerClient(builder.build());
    }

    @Test
    void sendsTheTransferWithThePaymentIdAsTheIdempotencyKey() {
        Payment payment = payment();
        UUID transactionId = UUID.randomUUID();
        server.expect(requestTo("http://ledger/api/v1/transfers"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", payment.id().toString()))
                .andExpect(jsonPath("$.fromAccountId").value(payment.fromAccountId().toString()))
                .andExpect(jsonPath("$.toAccountId").value(payment.toAccountId().toString()))
                .andExpect(jsonPath("$.amountMinor").value(100000))
                .andExpect(jsonPath("$.currency").value("KZT"))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"transactionId\":\"" + transactionId + "\"}"));

        LedgerResult result = client.transfer(payment);

        assertThat(result).isEqualTo(new LedgerResult.Completed(transactionId));
        server.verify();
    }

    @Test
    void aRefusalFromTheLedgerIsRejectedWithItsCode() {
        server.expect(requestTo("http://ledger/api/v1/transfers"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"type\":\"urn:ledgerly:problem:insufficient-funds\",\"status\":422}"));

        assertThat(client.transfer(payment())).isEqualTo(new LedgerResult.Rejected("insufficient-funds"));
    }

    @Test
    void aServerErrorLeavesTheOutcomeUnknown() {
        server.expect(requestTo("http://ledger/api/v1/transfers")).andRespond(withServerError());

        assertThat(client.transfer(payment())).isInstanceOf(LedgerResult.Unavailable.class);
    }

    @Test
    void aNetworkFailureLeavesTheOutcomeUnknown() {
        server.expect(requestTo("http://ledger/api/v1/transfers"))
                .andRespond(withException(new IOException("Read timed out")));

        assertThat(client.transfer(payment())).isInstanceOf(LedgerResult.Unavailable.class);
    }

    private static Payment payment() {
        OffsetDateTime now = OffsetDateTime.now();
        return new Payment(UUID.randomUUID(), "key-1", "hash", UUID.randomUUID(), UUID.randomUUID(),
                100_000, "KZT", PaymentStatus.PENDING, null, null, 0, now, now);
    }
}