package com.project.ledgercore.hold;

import java.util.UUID;

public record CaptureResponse(UUID holdId, UUID transactionId, long capturedMinor, String currency) {

    static CaptureResponse from(Capture capture) {
        return new CaptureResponse(
                capture.holdId(),
                capture.transactionId(),
                capture.captured().minorUnits(),
                capture.captured().currency().getCurrencyCode());
    }
}