package com.project.ledgercore.reversal;

import com.project.ledgercore.idempotency.IdempotencyKeys;
import com.project.ledgercore.idempotency.IdempotencyService;
import com.project.ledgercore.idempotency.IdempotencyService.Outcome;
import com.project.ledgercore.idempotency.RequestFingerprint;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions/{id}/reversals")
public class ReversalController {

    private static final String SCOPE = "reversal";

    private final ReversalService reversals;
    private final IdempotencyService idempotency;

    public ReversalController(ReversalService reversals, IdempotencyService idempotency) {
        this.reversals = reversals;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<ReversalResponse> reverse(
            @PathVariable UUID id,
            @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody(required = false) ReversalRequest request) {
        String key = IdempotencyKeys.require(idempotencyKey);
        String reason = request == null ? null : request.reason();
        String fingerprint = RequestFingerprint.of(SCOPE, id.toString(), reason == null ? "" : reason);

        Outcome<ReversalResponse> outcome = idempotency.execute(SCOPE, key, fingerprint, ReversalResponse.class,
                () -> new ReversalResponse(reversals.reverse(id, reason), id));

        return ResponseEntity.status(HttpStatus.CREATED)
                .header(IdempotencyKeys.REPLAYED_HEADER, String.valueOf(outcome.replayed()))
                .body(outcome.response());
    }
}