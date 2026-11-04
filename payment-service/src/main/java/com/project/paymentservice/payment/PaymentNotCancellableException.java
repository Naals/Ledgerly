package com.project.paymentservice.payment;

import com.project.paymentservice.common.ConflictException;
import java.util.UUID;

public class PaymentNotCancellableException extends ConflictException {

    public PaymentNotCancellableException(UUID paymentId, String reason) {
        super("payment-not-cancellable", "Payment " + paymentId + " cannot be cancelled: " + reason);
    }
}