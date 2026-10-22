package com.project.ledgercore.cash;

import com.project.ledgercore.common.Money;
import com.project.ledgercore.idempotency.IdempotencyKeys;
import com.project.ledgercore.idempotency.IdempotencyService;
import com.project.ledgercore.idempotency.RequestFingerprint;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Locale;
import java.util.function.Function;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}")
public class CashController {

    private final CashService cash;
    private final IdempotencyService idempotency;

    public CashController(CashService cash, IdempotencyService idempotency) {
        this.cash = cash;
        this.idempotency = idempotency;
    }

    @PostMapping("/deposits")
    public ResponseEntity<CashResponse> deposit(
            @PathVariable UUID accountId,
            @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CashRequest request) {
        return execute("deposit", accountId, idempotencyKey, request, amount -> cash.deposit(accountId, amount));
    }

    @PostMapping("/withdrawals")
    public ResponseEntity<CashResponse> withdraw(
            @PathVariable UUID accountId,
            @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CashRequest request) {
        return execute("withdrawal", accountId, idempotencyKey, request, amount -> cash.withdraw(accountId, amount));
    }

    private ResponseEntity<CashResponse> execute(
            String scope, UUID accountId, String idempotencyKey, CashRequest request,
            Function<Money, UUID> operation) {
        String key = IdempotencyKeys.require(idempotencyKey);
        Money amount = request.toMoney();
        String fingerprint = RequestFingerprint.of(scope, accountId.toString(),
                Long.toString(amount.minorUnits()), amount.currency().getCurrencyCode());

        IdempotencyService.Outcome<CashResponse> outcome = idempotency.execute(scope, key, fingerprint, CashResponse.class, () -> {
            UUID transactionId = operation.apply(amount);
            return new CashResponse(transactionId, accountId, scope.toUpperCase(Locale.ROOT),
                    amount.minorUnits(), amount.currency().getCurrencyCode());
        });

        return ResponseEntity.status(HttpStatus.CREATED)
                .header(IdempotencyKeys.REPLAYED_HEADER, String.valueOf(outcome.replayed()))
                .body(outcome.response());
    }
}
