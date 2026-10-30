package com.project.paymentservice.payment;

import com.project.paymentservice.common.NotFoundException;
import java.util.UUID;

public class PaymentNotFoundException extends NotFoundException {

    public PaymentNotFoundException(UUID paymentId) {
        super("payment-not-found", "Payment " + paymentId + " does not exist");
    }
}