package com.project.paymentservice.events.lag;

/** Lag against retained offsets; an invalid committed position is deliberately unknown. */
public record PartitionLag(double lag, boolean uncommitted, boolean invalidOffset) {

    public static PartitionLag between(long earliest, long latest, Long committed) {
        boolean uncommitted = committed == null;
        if (earliest < 0 || latest < earliest
                || (!uncommitted && (committed < earliest || committed > latest))) {
            return new PartitionLag(Double.NaN, uncommitted, true);
        }
        long position = uncommitted ? earliest : committed;
        return new PartitionLag(latest - position, uncommitted, false);
    }
}
