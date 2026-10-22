package com.project.ledgercore.transfer;

import com.project.ledgercore.common.Money;
import com.project.ledgercore.idempotency.IdempotencyKeys;
import com.project.ledgercore.idempotency.IdempotencyService;
import com.project.ledgercore.idempotency.RequestFingerprint;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private static final String SCOPE = "transfer";

    private final TransferService transfers;
    private final IdempotencyService idempotency;

    public TransferController(TransferService transfers, IdempotencyService idempotency) {
        this.transfers = transfers;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(
            @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        String key = IdempotencyKeys.require(idempotencyKey);
        Money amount = request.toMoney();
        String fingerprint = RequestFingerprint.of(SCOPE,
                request.fromAccountId().toString(),
                request.toAccountId().toString(),
                Long.toString(amount.minorUnits()),
                amount.currency().getCurrencyCode());

        IdempotencyService.Outcome<TransferResponse> outcome = idempotency.execute(SCOPE, key, fingerprint, TransferResponse.class, () -> {
            UUID transactionId = transfers.transfer(request.fromAccountId(), request.toAccountId(), amount);
            return new TransferResponse(transactionId, request.fromAccountId(), request.toAccountId(),
                    amount.minorUnits(), amount.currency().getCurrencyCode());
        });

        return ResponseEntity.status(HttpStatus.CREATED)
                .header(IdempotencyKeys.REPLAYED_HEADER, String.valueOf(outcome.replayed()))
                .body(outcome.response());
    }
}