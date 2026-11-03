package com.project.paymentservice.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.project.paymentservice.payment.CaptureMode;
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
    void sendsTheTransferWithThePaymentIdAsTheIdempotencyKeyAndTheReference() {
        Payment payment = payment();
        UUID transactionId = UUID.randomUUID();
        server.expect(requestTo("http://ledger/api/v1/transfers"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", payment.id().toString()))
                .andExpect(jsonPath("$.reference").value(payment.id().toString()))
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
                100_000, "KZT", CaptureMode.AUTOMATIC, PaymentStatus.PENDING, null, null, null, null, 0, now, now);
    }

    private static String holdJson(UUID id, String status, UUID transaction) {
        return """
                {"id": "%s", "status": "%s", "expiresAt": "2026-11-03T09:30:00+05:00", "ledgerTransactionId": %s}
                """.formatted(id, status, transaction == null ? "null" : "\"" + transaction + "\"");
    }

    private static Payment authorizedPayment(UUID holdId) {
        OffsetDateTime now = OffsetDateTime.now();
        return new Payment(UUID.randomUUID(), "key-1", "hash", UUID.randomUUID(), UUID.randomUUID(),
                100_000, "KZT", CaptureMode.MANUAL, PaymentStatus.AUTHORIZED, null, null, holdId, now.plusHours(1),
                0, now, now);
    }

    @Test
    void placesTheHoldOnThePayersAccountWithThePaymentIdAsKeyAndReference() {
        Payment payment = payment();
        UUID holdId = UUID.randomUUID();
        server.expect(requestTo("http://ledger/api/v1/accounts/" + payment.fromAccountId() + "/holds"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", payment.id().toString()))
                .andExpect(jsonPath("$.toAccountId").value(payment.toAccountId().toString()))
                .andExpect(jsonPath("$.amountMinor").value(100000))
                .andExpect(jsonPath("$.currency").value("KZT"))
                .andExpect(jsonPath("$.reference").value(payment.id().toString()))
                .andExpect(jsonPath("$.ttlSeconds").value(86400))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body(holdJson(holdId, "ACTIVE", null)));

        LedgerOutcome<HoldInfo> outcome = client.authorize(payment);

        assertThat(outcome).isInstanceOfSatisfying(LedgerOutcome.Done.class,
                done -> assertThat(((HoldInfo) done.value()).id()).isEqualTo(holdId));
        server.verify();
    }

    @Test
    void capturesTheHoldWithThePaymentIdAsTheIdempotencyKey() {
        UUID holdId = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();
        Payment payment = authorizedPayment(holdId);
        server.expect(requestTo("http://ledger/api/v1/holds/" + holdId + "/capture"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", payment.id().toString()))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"holdId\":\"" + holdId + "\",\"transactionId\":\"" + transaction + "\"}"));

        assertThat(client.capture(payment)).isEqualTo(new LedgerOutcome.Done<>(transaction));
        server.verify();
    }

    @Test
    void aCaptureTheLedgerRefusesIsRejectedWithItsCode() {
        UUID holdId = UUID.randomUUID();
        server.expect(requestTo("http://ledger/api/v1/holds/" + holdId + "/capture"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"type\":\"urn:ledgerly:problem:hold-expired\"}"));

        assertThat(client.capture(authorizedPayment(holdId))).isEqualTo(new LedgerOutcome.Rejected<UUID>("hold-expired"));
    }

    @Test
    void releasesAndReadsAHoldByItsId() {
        UUID holdId = UUID.randomUUID();
        server.expect(requestTo("http://ledger/api/v1/holds/" + holdId + "/release"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body(holdJson(holdId, "RELEASED", null)));
        server.expect(requestTo("http://ledger/api/v1/holds/" + holdId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body(holdJson(holdId, "RELEASED", null)));

        assertThat(client.release(authorizedPayment(holdId))).isInstanceOf(LedgerOutcome.Done.class);
        assertThat(client.findHold(holdId)).isInstanceOf(LedgerOutcome.Done.class);
        server.verify();
    }

    @Test
    void aLedgerThatCannotBeReachedLeavesEveryHoldCallUnavailable() {
        UUID holdId = UUID.randomUUID();
        server.expect(requestTo("http://ledger/api/v1/holds/" + holdId + "/capture"))
                .andRespond(withException(new IOException("Read timed out")));
        server.expect(requestTo("http://ledger/api/v1/holds/" + holdId))
                .andRespond(withException(new IOException("Connection refused")));

        assertThat(client.capture(authorizedPayment(holdId))).isInstanceOf(LedgerOutcome.Unavailable.class);
        assertThat(client.findHold(holdId)).isInstanceOf(LedgerOutcome.Unavailable.class);
    }
}