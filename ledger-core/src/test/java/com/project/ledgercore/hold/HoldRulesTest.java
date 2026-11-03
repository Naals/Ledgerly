package com.project.ledgercore.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.common.BadRequestException;
import com.project.ledgercore.common.Money;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class HoldRulesTest {

    private final Money held = Money.of(100_000, "KZT");

    @Test
    void aHoldWithoutATtlLastsTheDefault() {
        assertThat(HoldRules.resolveTtl(null)).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void aTtlBetweenOneMinuteAndSevenDaysIsAccepted() {
        assertThat(HoldRules.resolveTtl(60L)).isEqualTo(Duration.ofMinutes(1));
        assertThat(HoldRules.resolveTtl(3_600L)).isEqualTo(Duration.ofHours(1));
        assertThat(HoldRules.resolveTtl(604_800L)).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void aTtlOutsideThatRangeIsRefused() {
        for (long seconds : new long[] {-1, 0, 59, 604_801, Long.MAX_VALUE}) {
            assertThatThrownBy(() -> HoldRules.resolveTtl(seconds))
                    .isInstanceOf(BadRequestException.class)
                    .extracting("code").isEqualTo("invalid-ttl");
        }
    }

    @Test
    void aCaptureWithoutAnAmountTakesTheWholeHold() {
        assertThat(HoldRules.resolveCaptureAmount(held, null)).isEqualTo(held);
    }

    @Test
    void aCaptureMayTakeLessThanTheHold() {
        assertThat(HoldRules.resolveCaptureAmount(held, 40_000L)).isEqualTo(Money.of(40_000, "KZT"));
        assertThat(HoldRules.resolveCaptureAmount(held, 100_000L)).isEqualTo(held);
        assertThat(HoldRules.resolveCaptureAmount(held, 1L)).isEqualTo(Money.of(1, "KZT"));
    }

    @Test
    void aCaptureMayNotTakeMoreThanTheHold() {
        assertThatThrownBy(() -> HoldRules.resolveCaptureAmount(held, 100_001L))
                .isInstanceOf(CaptureExceedsHoldException.class)
                .extracting("code").isEqualTo("capture-exceeds-hold");
    }

    @Test
    void aCaptureAmountMustBePositive() {
        for (long amount : new long[] {0, -1, Long.MIN_VALUE}) {
            assertThatThrownBy(() -> HoldRules.resolveCaptureAmount(held, amount))
                    .isInstanceOf(BadRequestException.class)
                    .extracting("code").isEqualTo("invalid-amount");
        }
    }
}