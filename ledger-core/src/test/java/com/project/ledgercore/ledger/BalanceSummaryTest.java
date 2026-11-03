package com.project.ledgercore.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.common.Money;
import org.junit.jupiter.api.Test;

class BalanceSummaryTest {

    @Test
    void availableIsTheBalanceMinusWhatIsHeld() {
        BalanceSummary summary = new BalanceSummary(Money.of(1_000, "KZT"), Money.of(300, "KZT"));

        assertThat(summary.available()).isEqualTo(Money.of(700, "KZT"));
    }

    @Test
    void whenNothingIsHeldEverythingIsAvailable() {
        BalanceSummary summary = new BalanceSummary(Money.of(1_000, "KZT"), Money.of(0, "KZT"));

        assertThat(summary.available()).isEqualTo(Money.of(1_000, "KZT"));
    }

    @Test
    void balanceAndHeldMustShareACurrency() {
        assertThatThrownBy(() -> new BalanceSummary(Money.of(1_000, "KZT"), Money.of(10, "USD")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}