package com.project.ledgercore.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.common.BadRequestException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdempotencyKeysTest {

    @Test
    void acceptsUuidsAndSimilarKeys() {
        String uuid = UUID.randomUUID().toString();

        assertThat(IdempotencyKeys.require(uuid)).isEqualTo(uuid);
        assertThat(IdempotencyKeys.require("order_2026.10-17")).isEqualTo("order_2026.10-17");
    }

    @Test
    void missingOrBlankKeyIsRejectedWithItsOwnCode() {
        assertThatThrownBy(() -> IdempotencyKeys.require(null))
                .isInstanceOf(BadRequestException.class)
                .extracting(e -> ((BadRequestException) e).code()).isEqualTo("idempotency-key-required");
        assertThatThrownBy(() -> IdempotencyKeys.require("  "))
                .isInstanceOf(BadRequestException.class)
                .extracting(e -> ((BadRequestException) e).code()).isEqualTo("idempotency-key-required");
    }

    @Test
    void malformedKeysAreRejected() {
        assertThatThrownBy(() -> IdempotencyKeys.require("short"))
                .isInstanceOf(BadRequestException.class)
                .extracting(e -> ((BadRequestException) e).code()).isEqualTo("invalid-idempotency-key");
        assertThatThrownBy(() -> IdempotencyKeys.require("has spaces and $ymbols"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> IdempotencyKeys.require("x".repeat(65)))
                .isInstanceOf(BadRequestException.class);
    }
}
