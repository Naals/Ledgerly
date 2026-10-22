package com.project.ledgercore.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    @Test
    void sameInputGivesTheSameFingerprint() {
        assertThat(RequestFingerprint.of("transfer", "a", "b", "100", "KZT"))
                .isEqualTo(RequestFingerprint.of("transfer", "a", "b", "100", "KZT"));
    }

    @Test
    void fingerprintIsA64CharacterLowerCaseHexString() {
        assertThat(RequestFingerprint.of("x")).matches("[0-9a-f]{64}");
    }

    @Test
    void anyChangedPartChangesTheFingerprint() {
        String base = RequestFingerprint.of("transfer", "a", "b", "100", "KZT");

        assertThat(RequestFingerprint.of("transfer", "a", "b", "101", "KZT")).isNotEqualTo(base);
        assertThat(RequestFingerprint.of("transfer", "b", "a", "100", "KZT")).isNotEqualTo(base);
        assertThat(RequestFingerprint.of("deposit", "a", "b", "100", "KZT")).isNotEqualTo(base);
    }

    @Test
    void partBoundariesMatter() {
        assertThat(RequestFingerprint.of("ab", "c")).isNotEqualTo(RequestFingerprint.of("a", "bc"));
    }

    @Test
    void algorithmIsPinnedBecauseStoredFingerprintsMustStayComparable() {
        assertThat(RequestFingerprint.of("abc"))
                .isEqualTo("aab5f9ae99b2e38fb462025c8f72f570c9c811705d2a4277dc855d7fa293fe97");
    }
}