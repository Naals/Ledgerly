package com.project.ledgercore.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.common.Money;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostingRequestTest {

    private final UUID cashInBank = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();
    private final UUID fees = UUID.randomUUID();

    @Test
    void acceptsABalancedTransfer() {
        assertThatCode(() -> new PostingRequest(TransactionType.TRANSFER, "rent", List.of(
                PostingLine.debit(customer, Money.of(1_000, "KZT")),
                PostingLine.credit(cashInBank, Money.of(1_000, "KZT")))))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsASplitAcrossThreeLines() {
        PostingRequest request = new PostingRequest(TransactionType.TRANSFER, null, List.of(
                PostingLine.debit(customer, Money.of(1_000, "KZT")),
                PostingLine.credit(cashInBank, Money.of(900, "KZT")),
                PostingLine.credit(fees, Money.of(100, "KZT"))));

        assertThat(request.lines()).hasSize(3);
    }

    @Test
    void needsAtLeastTwoLines() {
        assertThatThrownBy(() -> new PostingRequest(TransactionType.DEPOSIT, null, List.of(
                PostingLine.credit(customer, Money.of(1, "KZT")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two lines");
    }

    @Test
    void rejectsLinesThatDoNotBalance() {
        assertThatThrownBy(() -> new PostingRequest(TransactionType.TRANSFER, null, List.of(
                PostingLine.debit(customer, Money.of(100, "KZT")),
                PostingLine.credit(cashInBank, Money.of(50, "KZT")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not balance for KZT")
                .hasMessageContaining("sum = -50");
    }

    @Test
    void eachCurrencyMustBalanceOnItsOwn() {
        assertThatThrownBy(() -> new PostingRequest(TransactionType.TRANSFER, null, List.of(
                PostingLine.debit(customer, Money.of(100, "USD")),
                PostingLine.credit(cashInBank, Money.of(100, "KZT")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not balance");
    }

    @Test
    void rejectsZeroAmountLines() {
        assertThatThrownBy(() -> new PostingRequest(TransactionType.TRANSFER, null, List.of(
                new PostingLine(customer, Money.of(0, "KZT")),
                new PostingLine(cashInBank, Money.of(0, "KZT")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be zero");
    }

    @Test
    void creditAndDebitRequirePositiveAmounts() {
        assertThatThrownBy(() -> PostingLine.credit(customer, Money.of(-5, "KZT")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PostingLine.debit(customer, Money.of(0, "KZT")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void debitIsStoredAsANegativeAmount() {
        assertThat(PostingLine.debit(customer, Money.of(70, "KZT")).amount()).isEqualTo(Money.of(-70, "KZT"));
        assertThat(PostingLine.credit(customer, Money.of(70, "KZT")).amount()).isEqualTo(Money.of(70, "KZT"));
    }

    @Test
    void descriptionMustFitTheColumn() {
        String tooLong = "x".repeat(PostingRequest.MAX_DESCRIPTION_LENGTH + 1);

        assertThatThrownBy(() -> new PostingRequest(TransactionType.TRANSFER, tooLong, List.of(
                PostingLine.debit(customer, Money.of(1, "KZT")),
                PostingLine.credit(cashInBank, Money.of(1, "KZT")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 255");
    }

    @Test
    void linesAreDefensivelyCopied() {
        java.util.ArrayList<PostingLine> mutable = new java.util.ArrayList<>(List.of(
                PostingLine.debit(customer, Money.of(1, "KZT")),
                PostingLine.credit(cashInBank, Money.of(1, "KZT"))));
        PostingRequest request = new PostingRequest(TransactionType.TRANSFER, null, mutable);

        mutable.clear();

        assertThat(request.lines()).hasSize(2);
    }

    @Test
    void anAccountMayAppearInOnlyOneLine() {
        assertThatThrownBy(() -> new PostingRequest(TransactionType.TRANSFER, null, List.of(
                PostingLine.debit(customer, Money.of(100, "KZT")),
                PostingLine.credit(customer, Money.of(100, "KZT")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most one line");
    }
}
