package com.project.paymentservice.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

class CorrelationContextTest {

    @AfterEach
    void clearContext() {
        MDC.remove(CorrelationContext.MDC_KEY);
    }

    @Test
    void nestedScopesRestoreTheirParentAndLeaveOtherMdcFieldsUntouched() {
        MDC.put("unrelated", "keep");
        try (CorrelationContext outer = CorrelationContext.open("outer")) {
            assertThat(CorrelationContext.current()).isEqualTo("outer");
            try (CorrelationContext inner = CorrelationContext.open("inner")) {
                assertThat(CorrelationContext.current()).isEqualTo("inner");
            }
            assertThat(CorrelationContext.current()).isEqualTo("outer");
            assertThat(MDC.get("unrelated")).isEqualTo("keep");
        } finally {
            MDC.remove("unrelated");
        }
        assertThat(CorrelationContext.current()).isNull();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "has spaces", "line\nbreak", "unicode-Ж", "bad;header"})
    void missingOrUnsafeIdsAreReplaced(String input) {
        assertThat(CorrelationContext.isValid(input)).isFalse();
        try (CorrelationContext context = CorrelationContext.open(input)) {
            assertThat(context.id()).matches("[0-9a-f-]{36}");
            assertThat(CorrelationContext.current()).isEqualTo(context.id());
        }
    }

    @Test
    void idsHaveABoundedSafeCharacterSet() {
        assertThat(CorrelationContext.isValid("aZ09._-".repeat(9))).isTrue();
        assertThat(CorrelationContext.isValid("a".repeat(64))).isTrue();
        assertThat(CorrelationContext.isValid("a".repeat(65))).isFalse();
        try (CorrelationContext context = CorrelationContext.open("a".repeat(65))) {
            assertThat(context.id()).hasSize(36);
        }
    }

    @Test
    void restoresThePreviousContextAfterAnException() {
        MDC.put(CorrelationContext.MDC_KEY, "parent");
        assertThatThrownBy(() -> {
            try (CorrelationContext ignored = CorrelationContext.open("request")) {
                throw new IllegalStateException("failed request");
            }
        }).isInstanceOf(IllegalStateException.class);
        assertThat(CorrelationContext.current()).isEqualTo("parent");
    }

    @Test
    void backgroundScopesKeepCurrentContextOrUseTheirStableFallback() {
        try (CorrelationContext ignored = CorrelationContext.openOrCurrent("payment-42")) {
            assertThat(CorrelationContext.current()).isEqualTo("payment-42");
            try (CorrelationContext inner = CorrelationContext.openOrCurrent("another-operation")) {
                assertThat(inner.id()).isEqualTo("payment-42");
            }
        }
        assertThat(CorrelationContext.current()).isNull();
    }

    @Test
    void unsafeMdcValuesAreNotPropagated() {
        MDC.put(CorrelationContext.MDC_KEY, "bad\nheader");
        assertThat(CorrelationContext.current()).isNull();
        try (CorrelationContext ignored = CorrelationContext.openOrCurrent("safe-fallback")) {
            assertThat(CorrelationContext.current()).isEqualTo("safe-fallback");
        }
        assertThat(MDC.get(CorrelationContext.MDC_KEY)).isEqualTo("bad\nheader");
    }
}
