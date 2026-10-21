package com.project.ledgercore.idempotency;

import com.project.ledgercore.common.BadRequestException;

import java.util.regex.Pattern;

public final class IdempotencyKeys {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    private IdempotencyKeys() {
    }

    /** Returns the key if the header is present and well formed, otherwise throws a 400-level error. */
    public static String require(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new BadRequestException("idempotency-key-required", HEADER + " header is required");
        }
        if (!VALID.matcher(headerValue).matches()) {
            throw new BadRequestException("invalid-idempotency-key",
                    HEADER + " must be 8-64 characters: letters, digits, '.', '_' or '-'");
        }
        return headerValue;
    }
}
