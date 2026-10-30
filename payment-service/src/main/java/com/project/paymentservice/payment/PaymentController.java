package com.project.paymentservice.payment;

import com.project.paymentservice.common.IdempotencyKeys;
import jakarta.validation.Valid;
import java.net.URI;
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    /** 201 for a new payment, 200 when the same request was already made with this key. */
    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        String key = IdempotencyKeys.require(idempotencyKey);
        CreatedPayment created = payments.create(key, new CreatePayment(
                request.fromAccountId(), request.toAccountId(), request.amountMinor(), request.currency()));

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.payment().id())
                .toUri();
        return ResponseEntity.status(created.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .location(location)
                .header(IdempotencyKeys.REPLAYED_HEADER, String.valueOf(created.replayed()))
                .body(PaymentResponse.from(created.payment()));
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable UUID id) {
        return PaymentResponse.from(payments.get(id));
    }
}