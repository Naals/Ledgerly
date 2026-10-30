package com.project.paymentservice.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdempotencyKeysTest {

    @Test
    void acceptsUuidsAndSimilarKeys() {
        String uuid = UUID.randomUUID().toString();

        assertThat(IdempotencyKeys.require(uuid)).isEqualTo(uuid);
    }

    @Test
    void rejectsMissingAndMalformedKeys() {
        assertThatThrownBy(() -> IdempotencyKeys.require(null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> IdempotencyKeys.require("short")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> IdempotencyKeys.require("has spaces and $ymbols"))
                .isInstanceOf(BadRequestException.class);
    }
}