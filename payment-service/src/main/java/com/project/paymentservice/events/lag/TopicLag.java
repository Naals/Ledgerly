package com.project.paymentservice.events.lag;

import java.util.Collection;

public record TopicLag(double lag, double maxLag, int uncommittedPartitions, int invalidOffsets) {

    public static TopicLag from(Collection<PartitionLag> partitions) {
        if (partitions.isEmpty()) {
            throw new IllegalArgumentException("A topic must contain at least one partition");
        }
        double lag = 0;
        double maxLag = 0;
        int uncommitted = 0;
        int invalid = 0;
        for (PartitionLag partition : partitions) {
            lag += partition.lag();
            maxLag = Math.max(maxLag, partition.lag());
            uncommitted += partition.uncommitted() ? 1 : 0;
            invalid += partition.invalidOffset() ? 1 : 0;
        }
        return new TopicLag(lag, maxLag, uncommitted, invalid);
    }
}
