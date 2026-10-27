package com.project.ledgercore.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BackoffTest {

    @Test
    void doublesWithEveryFailedAttempt() {
        assertThat(Backoff.delaySecondsAfter(1)).isEqualTo(1);
        assertThat(Backoff.delaySecondsAfter(2)).isEqualTo(2);
        assertThat(Backoff.delaySecondsAfter(3)).isEqualTo(4);
        assertThat(Backoff.delaySecondsAfter(9)).isEqualTo(256);
    }

    @Test
    void isCappedAtFiveMinutes() {
        assertThat(Backoff.delaySecondsAfter(10)).isEqualTo(300);
        assertThat(Backoff.delaySecondsAfter(100)).isEqualTo(300);
        assertThat(Backoff.delaySecondsAfter(Integer.MAX_VALUE)).isEqualTo(300);
    }

    @Test
    void treatsNonsenseAsTheFirstFailure() {
        assertThat(Backoff.delaySecondsAfter(0)).isEqualTo(1);
        assertThat(Backoff.delaySecondsAfter(-5)).isEqualTo(1);
    }

    @Test
    void neverShrinksAsAttemptsGrow() {
        long previous = 0;
        for (int attempts = 1; attempts <= 40; attempts++) {
            long delay = Backoff.delaySecondsAfter(attempts);
            assertThat(delay).isGreaterThanOrEqualTo(previous);
            previous = delay;
        }
    }
}