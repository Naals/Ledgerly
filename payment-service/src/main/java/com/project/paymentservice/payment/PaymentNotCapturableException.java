package com.project.paymentservice.payment;

import com.project.paymentservice.common.ConflictException;
import java.util.UUID;

public class PaymentNotCapturableException extends ConflictException {

    public PaymentNotCapturableException(UUID paymentId, String reason) {
        super("payment-not-capturable", "Payment " + paymentId + " cannot be captured: " + reason);
    }
}