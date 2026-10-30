package com.project.paymentservice.ledger;

import com.project.paymentservice.payment.Payment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Calls ledger-core over HTTP. */
public class LedgerClient implements LedgerGateway {

    private final RestClient ledger;

    public LedgerClient(RestClient ledger) {
        this.ledger = ledger;
    }

    @Override
    public LedgerResult transfer(Payment payment) {
        TransferRequest body = new TransferRequest(
                payment.fromAccountId(), payment.toAccountId(), payment.amountMinor(), payment.currency());
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

    private static String readBody(ClientHttpResponse response) throws IOException {
        return StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
    }

    record TransferRequest(UUID fromAccountId, UUID toAccountId, long amountMinor, String currency) {
    }
}