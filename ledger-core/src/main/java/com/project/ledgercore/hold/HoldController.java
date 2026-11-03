package com.project.ledgercore.hold;

import com.project.ledgercore.common.Money;
import com.project.ledgercore.idempotency.IdempotencyKeys;
import com.project.ledgercore.idempotency.IdempotencyService;
import com.project.ledgercore.idempotency.IdempotencyService.Outcome;
import com.project.ledgercore.idempotency.RequestFingerprint;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HoldController {

    private static final String PLACE_SCOPE = "hold";
    private static final String CAPTURE_SCOPE = "capture";

    private final HoldService holds;
    private final IdempotencyService idempotency;

    public HoldController(HoldService holds, IdempotencyService idempotency) {
        this.holds = holds;
        this.idempotency = idempotency;
    }

    @PostMapping("/accounts/{accountId}/holds")
    public ResponseEntity<HoldResponse> place(
            @PathVariable UUID accountId,
            @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody PlaceHoldRequest request) {
        String key = IdempotencyKeys.require(idempotencyKey);
        Money amount = request.toMoney();
        String fingerprint = RequestFingerprint.of(PLACE_SCOPE,
                accountId.toString(),
                request.toAccountId().toString(),
                Long.toString(amount.minorUnits()),
                amount.currency().getCurrencyCode(),
                String.valueOf(request.reference()),
                String.valueOf(request.ttlSeconds()));

        Outcome<HoldResponse> outcome = idempotency.execute(PLACE_SCOPE, key, fingerprint, HoldResponse.class,
                () -> HoldResponse.from(holds.place(
                        accountId, request.toAccountId(), amount, request.reference(), request.ttlSeconds())));

        return ResponseEntity.status(HttpStatus.CREATED)
                .header(IdempotencyKeys.REPLAYED_HEADER, String.valueOf(outcome.replayed()))
                .body(outcome.response());
    }

    @GetMapping("/holds/{id}")
    public HoldResponse get(@PathVariable UUID id) {
        return HoldResponse.from(holds.get(id));
    }

    @PostMapping("/holds/{id}/capture")
    public ResponseEntity<CaptureResponse> capture(
            @PathVariable UUID id,
            @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody(required = false) CaptureHoldRequest request) {
        String key = IdempotencyKeys.require(idempotencyKey);
        Long amountMinor = request == null ? null : request.amountMinor();
        String fingerprint = RequestFingerprint.of(CAPTURE_SCOPE, id.toString(),
                amountMinor == null ? "full" : Long.toString(amountMinor));

        Outcome<CaptureResponse> outcome = idempotency.execute(CAPTURE_SCOPE, key, fingerprint, CaptureResponse.class,
                () -> CaptureResponse.from(holds.capture(id, amountMinor)));

        return ResponseEntity.status(HttpStatus.CREATED)
                .header(IdempotencyKeys.REPLAYED_HEADER, String.valueOf(outcome.replayed()))
                .body(outcome.response());
    }

    @PostMapping("/holds/{id}/release")
    public HoldResponse release(@PathVariable UUID id) {
        return HoldResponse.from(holds.release(id));
    }
}