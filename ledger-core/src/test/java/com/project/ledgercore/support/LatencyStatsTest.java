package com.project.ledgercore.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class LatencyStatsTest {

    /** 1 ms, 2 ms ... 100 ms, expressed in nanoseconds. */
    private static List<Long> oneToHundredMillis() {
        return LongStream.rangeClosed(1, 100).map(ms -> ms * 1_000_000L).boxed().toList();
    }

    @Test
    void percentilesUseTheNearestRankMethod() {
        LatencyStats stats = LatencyStats.of(oneToHundredMillis());

        assertThat(stats.percentileMillis(50)).isEqualTo(50.0);
        assertThat(stats.percentileMillis(95)).isEqualTo(95.0);
        assertThat(stats.percentileMillis(99)).isEqualTo(99.0);
        assertThat(stats.percentileMillis(100)).isEqualTo(100.0);
    }

    @Test
    void countMeanAndMaximum() {
        LatencyStats stats = LatencyStats.of(oneToHundredMillis());

        assertThat(stats.count()).isEqualTo(100);
        assertThat(stats.meanMillis()).isEqualTo(50.5);
        assertThat(stats.maxMillis()).isEqualTo(100.0);
    }

    @Test
    void theOrderOfTheInputDoesNotMatter() {
        List<Long> shuffled = new ArrayList<>(oneToHundredMillis());
        Collections.shuffle(shuffled, new Random(1));

        assertThat(LatencyStats.of(shuffled).percentileMillis(95)).isEqualTo(95.0);
    }

    @Test
    void aSingleMeasurementIsEveryPercentile() {
        LatencyStats stats = LatencyStats.of(List.of(2_500_000L));

        assertThat(stats.percentileMillis(1)).isEqualTo(2.5);
        assertThat(stats.percentileMillis(99)).isEqualTo(2.5);
    }

    @Test
    void rejectsEmptyInputAndInvalidPercentiles() {
        assertThatThrownBy(() -> LatencyStats.of(List.of())).isInstanceOf(IllegalArgumentException.class);

        LatencyStats stats = LatencyStats.of(List.of(1_000_000L));
        assertThatThrownBy(() -> stats.percentileMillis(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stats.percentileMillis(101)).isInstanceOf(IllegalArgumentException.class);
    }
}