package com.project.paymentservice.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

import com.project.paymentservice.common.CorrelationContext;
import com.project.paymentservice.payment.CaptureMode;
import com.project.paymentservice.payment.Payment;
import com.project.paymentservice.payment.PaymentStatus;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class LedgerClientCorrelationTest {

    enum Operation { TRANSFER, AUTHORIZE, CAPTURE, RELEASE, FIND_HOLD }

    private final Payment payment = payment();
    private final MockRestServiceServer server;
    private final LedgerClient client;

    LedgerClientCorrelationTest() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ledger");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new LedgerClient(builder.build());
    }

    @AfterEach
    void clearContext() {
        MDC.remove(CorrelationContext.MDC_KEY);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void everyLedgerCallForwardsTheCurrentRequestId(Operation operation) {
        server.expect(requestTo(url(operation)))
                .andExpect(header(CorrelationContext.HEADER, "request-42"))
                .andRespond(withServerError());

        try (CorrelationContext ignored = CorrelationContext.open("request-42")) {
            call(operation);
            assertThat(CorrelationContext.current()).isEqualTo("request-42");
        }
        assertThat(CorrelationContext.current()).isNull();
        server.verify();
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void backgroundCallsHaveAStableFallbackWithoutCreatingThreadContext(Operation operation) {
        String fallback = operation == Operation.FIND_HOLD
                ? payment.ledgerHoldId().toString() : payment.id().toString();
        server.expect(requestTo(url(operation)))
                .andExpect(header(CorrelationContext.HEADER, fallback))
                .andRespond(withServerError());

        call(operation);

        assertThat(CorrelationContext.current()).isNull();
        server.verify();
    }

    @Test
    void invalidMdcCannotBecomeAnOutgoingHttpHeader() {
        MDC.put(CorrelationContext.MDC_KEY, "unsafe\nheader");
        server.expect(requestTo(url(Operation.TRANSFER)))
                .andExpect(header(CorrelationContext.HEADER, payment.id().toString()))
                .andRespond(withServerError());

        client.transfer(payment);

        assertThat(MDC.get(CorrelationContext.MDC_KEY)).isEqualTo("unsafe\nheader");
        server.verify();
    }

    @Test
    void aSharedClientDoesNotRetainThePreviousRequestsIdAfterANetworkFailure() {
        server.expect(requestTo(url(Operation.TRANSFER)))
                .andExpect(header(CorrelationContext.HEADER, "first-request"))
                .andRespond(withException(new IOException("timeout")));
        server.expect(requestTo(url(Operation.TRANSFER)))
                .andExpect(header(CorrelationContext.HEADER, payment.id().toString()))
                .andRespond(withServerError());

        try (CorrelationContext ignored = CorrelationContext.open("first-request")) {
            assertThat(client.transfer(payment)).isInstanceOf(LedgerResult.Unavailable.class);
        }
        client.transfer(payment);

        assertThat(CorrelationContext.current()).isNull();
        server.verify();
    }

    private String url(Operation operation) {
        return "http://ledger" + switch (operation) {
            case TRANSFER -> "/api/v1/transfers";
            case AUTHORIZE -> "/api/v1/accounts/" + payment.fromAccountId() + "/holds";
            case CAPTURE -> "/api/v1/holds/" + payment.ledgerHoldId() + "/capture";
            case RELEASE -> "/api/v1/holds/" + payment.ledgerHoldId() + "/release";
            case FIND_HOLD -> "/api/v1/holds/" + payment.ledgerHoldId();
        };
    }

    private void call(Operation operation) {
        switch (operation) {
            case TRANSFER -> client.transfer(payment);
            case AUTHORIZE -> client.authorize(payment);
            case CAPTURE -> client.capture(payment);
            case RELEASE -> client.release(payment);
            case FIND_HOLD -> client.findHold(payment.ledgerHoldId());
        }
    }

    private static Payment payment() {
        OffsetDateTime now = OffsetDateTime.now();
        return new Payment(UUID.randomUUID(), "key-1", "hash", UUID.randomUUID(), UUID.randomUUID(),
                100_000, "KZT", CaptureMode.MANUAL, PaymentStatus.AUTHORIZED, null, null,
                UUID.randomUUID(), now.plusHours(1), 0, now, now);
    }
}
