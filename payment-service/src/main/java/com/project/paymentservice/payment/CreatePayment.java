package com.project.paymentservice.payment;

import java.util.UUID;

/** A request to move money between two accounts, in one step (the default) or authorized first and captured later. */
public record CreatePayment(
        UUID fromAccountId, UUID toAccountId, long amountMinor, String currency, CaptureMode captureMode) {

    public CreatePayment {
        captureMode = captureMode == null ? CaptureMode.AUTOMATIC : captureMode;
    }

    /** A one-step payment. */
    public CreatePayment(UUID fromAccountId, UUID toAccountId, long amountMinor, String currency) {
        this(fromAccountId, toAccountId, amountMinor, currency, CaptureMode.AUTOMATIC);
    }
}