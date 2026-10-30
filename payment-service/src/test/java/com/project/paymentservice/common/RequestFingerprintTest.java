package com.project.paymentservice.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    @Test
    void sameInputGivesTheSameLowerCaseHexFingerprint() {
        String fingerprint = RequestFingerprint.of("a", "b", "100", "KZT");

        assertThat(fingerprint).matches("[0-9a-f]{64}").isEqualTo(RequestFingerprint.of("a", "b", "100", "KZT"));
    }

    @Test
    void anyChangedPartOrBoundaryChangesIt() {
        assertThat(RequestFingerprint.of("a", "b", "101", "KZT")).isNotEqualTo(RequestFingerprint.of("a", "b", "100", "KZT"));
        assertThat(RequestFingerprint.of("ab", "c")).isNotEqualTo(RequestFingerprint.of("a", "bc"));
    }
}