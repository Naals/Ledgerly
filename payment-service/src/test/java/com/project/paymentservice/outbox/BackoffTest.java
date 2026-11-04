package com.project.paymentservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BackoffTest {

    @Test
    void waitsOneSecondAfterTheFirstFailureThenDoublesEachTime() {
        assertThat(Backoff.delaySecondsAfter(1)).isEqualTo(1);
        assertThat(Backoff.delaySecondsAfter(2)).isEqualTo(2);
        assertThat(Backoff.delaySecondsAfter(3)).isEqualTo(4);
        assertThat(Backoff.delaySecondsAfter(4)).isEqualTo(8);
    }

    @Test
    void neverWaitsMoreThanFiveMinutes() {
        assertThat(Backoff.delaySecondsAfter(9)).isEqualTo(256);
        assertThat(Backoff.delaySecondsAfter(10)).isEqualTo(300);
        assertThat(Backoff.delaySecondsAfter(1_000)).isEqualTo(300);
        assertThat(Backoff.delaySecondsAfter(Integer.MAX_VALUE)).isEqualTo(300);
    }

    @Test
    void treatsAnythingBelowOneAsTheFirstFailure() {
        assertThat(Backoff.delaySecondsAfter(0)).isEqualTo(1);
        assertThat(Backoff.delaySecondsAfter(-5)).isEqualTo(1);
    }
}