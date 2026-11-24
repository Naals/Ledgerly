package com.project.paymentservice.events.lag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PartitionLagTest {

    @Test
    void measuresLagFromTheNextCommittedOffsetIncludingZero() {
        assertThat(PartitionLag.between(0, 12, 0L)).isEqualTo(new PartitionLag(12, false, false));
        assertThat(PartitionLag.between(0, 12, 7L)).isEqualTo(new PartitionLag(5, false, false));
        assertThat(PartitionLag.between(0, 12, 12L)).isEqualTo(new PartitionLag(0, false, false));
    }

    @Test
    void anUncommittedPartitionUsesTheEarliestRetainedOffset() {
        assertThat(PartitionLag.between(4, 12, null)).isEqualTo(new PartitionLag(8, true, false));
        assertThat(PartitionLag.between(12, 12, null)).isEqualTo(new PartitionLag(0, true, false));
    }

    @Test
    void anOffsetOutsideTheRetainedRangeIsUnknownRatherThanZero() {
        for (long committed : new long[] {-1, 3, 13}) {
            PartitionLag result = PartitionLag.between(4, 12, committed);
            assertThat(result.lag()).isNaN();
            assertThat(result.invalidOffset()).isTrue();
            assertThat(result.uncommitted()).isFalse();
        }
    }

    @Test
    void invalidBrokerRangesCannotLookHealthy() {
        assertThat(PartitionLag.between(-1, 12, null)).isEqualTo(new PartitionLag(Double.NaN, true, true));
        assertThat(PartitionLag.between(12, 4, 12L)).isEqualTo(new PartitionLag(Double.NaN, false, true));
    }

    @Test
    void retainedRangesDoNotOverflowLongArithmetic() {
        assertThat(PartitionLag.between(0, Long.MAX_VALUE, null).lag()).isEqualTo((double) Long.MAX_VALUE);
    }
}
