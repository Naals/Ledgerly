package com.project.ledgercore.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class LockOrderTest {

    @Test
    void sortsByTheCanonicalTextForm() {
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID middle = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
        UUID high = UUID.fromString("ffffffff-0000-0000-0000-000000000000");

        List<UUID> sorted = Stream.of(high, low, middle).sorted(LockOrder.BY_ID).toList();

        assertThat(sorted).containsExactly(low, middle, high);
    }

    @Test
    void javasOwnUuidOrderingDisagreesWithPostgres() {
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID high = UUID.fromString("ffffffff-0000-0000-0000-000000000000");

        assertThat(LockOrder.BY_ID.compare(low, high)).isNegative();   // what PostgreSQL does
        assertThat(low.compareTo(high)).isPositive();                  // UUID.compareTo compares signed values
    }
}