package com.project.ledgercore.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.common.ConflictException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountLifecycleTest {

    @Test
    void anActiveAccountCanBeFrozenOrClosed() {
        assertThat(AccountLifecycle.isAllowed(AccountStatus.ACTIVE, AccountStatus.FROZEN)).isTrue();
        assertThat(AccountLifecycle.isAllowed(AccountStatus.ACTIVE, AccountStatus.CLOSED)).isTrue();
    }

    @Test
    void aFrozenAccountCanBeUnfrozenOrClosed() {
        assertThat(AccountLifecycle.isAllowed(AccountStatus.FROZEN, AccountStatus.ACTIVE)).isTrue();
        assertThat(AccountLifecycle.isAllowed(AccountStatus.FROZEN, AccountStatus.CLOSED)).isTrue();
    }

    @Test
    void aClosedAccountIsFinal() {
        assertThat(AccountLifecycle.isAllowed(AccountStatus.CLOSED, AccountStatus.ACTIVE)).isFalse();
        assertThat(AccountLifecycle.isAllowed(AccountStatus.CLOSED, AccountStatus.FROZEN)).isFalse();
    }

    @Test
    void askingForTheCurrentStatusIsAlwaysAllowed() {
        for (AccountStatus status : AccountStatus.values()) {
            assertThat(AccountLifecycle.isAllowed(status, status)).isTrue();
        }
    }

    @Test
    void aForbiddenMoveIsAConflictWithAStableCode() {
        UUID id = UUID.randomUUID();

        assertThatCode(() -> AccountLifecycle.requireAllowed(id, AccountStatus.ACTIVE, AccountStatus.FROZEN))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> AccountLifecycle.requireAllowed(id, AccountStatus.CLOSED, AccountStatus.ACTIVE))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("CLOSED")
                .hasMessageContaining("ACTIVE")
                .extracting(e -> ((ConflictException) e).code()).isEqualTo("invalid-status-transition");
    }
}