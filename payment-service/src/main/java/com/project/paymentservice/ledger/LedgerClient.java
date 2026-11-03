package com.project.paymentservice.ledger;

import com.project.paymentservice.payment.Payment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Calls ledger-core over HTTP. */
public class LedgerClient implements LedgerGateway {

    static final long DEFAULT_HOLD_TTL_SECONDS = 24 * 60 * 60;

    private final RestClient ledger;
    private final long holdTtlSeconds;

    public LedgerClient(RestClient ledger) {
        this(ledger, DEFAULT_HOLD_TTL_SECONDS);
    }

    /** @param holdTtlSeconds how long a hold lasts, so how long a payment can wait between authorization and capture */
    public LedgerClient(RestClient ledger, long holdTtlSeconds) {
        this.ledger = ledger;
        this.holdTtlSeconds = holdTtlSeconds;
    }

    @Override
    public LedgerResult transfer(Payment payment) {
        TransferRequest body = new TransferRequest(
                payment.fromAccountId(), payment.toAccountId(), payment.amountMinor(), payment.currency(),
                payment.id().toString());
        try {
            return ledger.post()
                    .uri("/api/v1/transfers")
                    .header("Idempotency-Key", payment.id().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> LedgerResponseInterpreter.interpret(
                            response.getStatusCode().value(), readBody(response)));
        } catch (ResourceAccessException e) {
            return new LedgerResult.Unavailable("The ledger could not be reached: " + e.getMessage());
        }
    }

    /** The payment id is the hold's reference too, so the capture's transfer carries it into its event (Day 8). */
    @Override
    public LedgerOutcome<HoldInfo> authorize(Payment payment) {
        HoldRequest body = new HoldRequest(payment.toAccountId(), payment.amountMinor(), payment.currency(),
                payment.id().toString(), holdTtlSeconds);
        return call(ledger.post()
                        .uri("/api/v1/accounts/{accountId}/holds", payment.fromAccountId())
                        .header("Idempotency-Key", payment.id().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body),
                LedgerResponseInterpreter::interpretHold);
    }

    @Override
    public LedgerOutcome<UUID> capture(Payment payment) {
        return call(ledger.post()
                        .uri("/api/v1/holds/{holdId}/capture", payment.ledgerHoldId())
                        .header("Idempotency-Key", payment.id().toString()),
                LedgerResponseInterpreter::interpretCapture);
    }

    @Override
    public LedgerOutcome<HoldInfo> release(Payment payment) {
        return call(ledger.post().uri("/api/v1/holds/{holdId}/release", payment.ledgerHoldId()),
                LedgerResponseInterpreter::interpretHold);
    }

    @Override
    public LedgerOutcome<HoldInfo> findHold(UUID holdId) {
        return call(ledger.get().uri("/api/v1/holds/{holdId}", holdId), LedgerResponseInterpreter::interpretHold);
    }

    private <T> LedgerOutcome<T> call(RestClient.RequestHeadersSpec<?> request,
                                      BiFunction<Integer, String, LedgerOutcome<T>> interpreter) {
        try {
            return request.exchange((httpRequest, response) ->
                    interpreter.apply(response.getStatusCode().value(), readBody(response)));
        } catch (ResourceAccessException e) {
            return new LedgerOutcome.Unavailable<>("The ledger could not be reached: " + e.getMessage());
        }
    }

    private static String readBody(ClientHttpResponse response) throws IOException {
        return StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
    }

    record TransferRequest(UUID fromAccountId, UUID toAccountId, long amountMinor, String currency, String reference) {
    }

    record HoldRequest(UUID toAccountId, long amountMinor, String currency, String reference, long ttlSeconds) {
    }
}