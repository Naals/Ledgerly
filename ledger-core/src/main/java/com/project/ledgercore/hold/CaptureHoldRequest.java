package com.project.ledgercore.hold;

import jakarta.validation.constraints.Positive;

/** Optional body of a capture: how much to take. Without it the whole hold is captured. */
public record CaptureHoldRequest(@Positive Long amountMinor) {
}