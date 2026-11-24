package com.project.paymentservice.events.lag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class TopicLagTest {

    @Test
    void aggregatesTotalAndMaximumWithoutHidingUncommittedPartitions() {
        assertThat(TopicLag.from(List.of(PartitionLag.between(0, 20, 7L), PartitionLag.between(4, 12, null))))
                .isEqualTo(new TopicLag(21, 13, 1, 0));
    }

    @Test
    void anyInvalidPartitionMakesBothTopicLagMeasuresUnknown() {
        TopicLag result = TopicLag.from(List.of(PartitionLag.between(0, 20, null),
                PartitionLag.between(4, 12, 2L), PartitionLag.between(4, 12, 13L)));
        assertThat(result.lag()).isNaN();
        assertThat(result.maxLag()).isNaN();
        assertThat(result.uncommittedPartitions()).isEqualTo(1);
        assertThat(result.invalidOffsets()).isEqualTo(2);
    }

    @Test
    void missingPartitionsCannotBeReportedAsZeroLag() {
        assertThatThrownBy(() -> TopicLag.from(List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
