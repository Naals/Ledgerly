package com.project.ledgercore.hold;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class HoldStatusTest {

    @Test
    void anActiveHoldCanBecomeAnyFinishedStatus() {
        assertThat(HoldStatus.ACTIVE.canMoveTo(HoldStatus.CAPTURED)).isTrue();
        assertThat(HoldStatus.ACTIVE.canMoveTo(HoldStatus.RELEASED)).isTrue();
        assertThat(HoldStatus.ACTIVE.canMoveTo(HoldStatus.EXPIRED)).isTrue();
    }

    @Test
    void anActiveHoldCannotStayActiveByMoving() {
        assertThat(HoldStatus.ACTIVE.canMoveTo(HoldStatus.ACTIVE)).isFalse();
    }

    @Test
    void aFinishedHoldNeverMovesAgain() {
        for (HoldStatus finished : EnumSet.of(HoldStatus.CAPTURED, HoldStatus.RELEASED, HoldStatus.EXPIRED)) {
            for (HoldStatus target : HoldStatus.values()) {
                assertThat(finished.canMoveTo(target)).as(finished + " -> " + target).isFalse();
            }
        }
    }
}