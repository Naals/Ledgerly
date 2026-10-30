package com.project.paymentservice.payment;

import com.project.paymentservice.common.BusinessRuleException;

public class IdempotencyKeyReuseException extends BusinessRuleException {

    public IdempotencyKeyReuseException(String key) {
        super("idempotency-key-reuse", "Idempotency key " + key + " was already used with a different request");
    }
}