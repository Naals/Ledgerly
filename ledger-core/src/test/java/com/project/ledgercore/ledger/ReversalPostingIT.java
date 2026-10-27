package com.project.ledgercore.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.common.ConflictException;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReversalPostingIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    LedgerService ledger;

    @Autowired
    AccountRepository accounts;

    private Account alice;
    private Account bob;
    private UUID transfer;

    @BeforeEach
    void postATransfer() {
        Account funding = accounts.create(NewAccount.system("rev-funding-" + UUID.randomUUID(), KZT));
        alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        bob = accounts.create(NewAccount.customer("bob-" + UUID.randomUUID(), KZT));
        ledger.post(new PostingRequest(TransactionType.DEPOSIT, "funding", List.of(
                PostingLine.debit(funding.id(), Money.of(1_000, "KZT")),
                PostingLine.credit(alice.id(), Money.of(1_000, "KZT")))));
        transfer = ledger.post(new PostingRequest(TransactionType.TRANSFER, "rent", List.of(
                PostingLine.debit(alice.id(), Money.of(400, "KZT")),
                PostingLine.credit(bob.id(), Money.of(400, "KZT")))));
    }

    @Test
    void aReversalPostingRestoresTheBalances() {
        reverse(transfer);

        assertThat(ledger.balanceOf(alice.id())).contains(Money.of(1_000, "KZT"));
        assertThat(ledger.balanceOf(bob.id())).contains(Money.zero(KZT));
    }

    @Test
    void aSecondReversalOfTheSameTransactionIsAConflict() {
        reverse(transfer);

        assertThatThrownBy(() -> reverse(transfer))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).code()).isEqualTo("already-reversed");
    }

    @Test
    void aReversalOfAReversalIsRefusedByTheDatabaseAtCommit() {
        UUID reversal = reverse(transfer);

        assertThatThrownBy(() -> ledger.post(new PostingRequest(TransactionType.REVERSAL, "again", List.of(
                PostingLine.debit(alice.id(), Money.of(400, "KZT")),
                PostingLine.credit(bob.id(), Money.of(400, "KZT"))), reversal)))
                .hasStackTraceContaining("reversal_of_reversal");
    }

    @Test
    void aReversalThatIsNotTheExactOppositeIsRefusedByTheDatabaseAtCommit() {
        assertThatThrownBy(() -> ledger.post(new PostingRequest(TransactionType.REVERSAL, "wrong", List.of(
                PostingLine.credit(alice.id(), Money.of(399, "KZT")),
                PostingLine.debit(bob.id(), Money.of(399, "KZT"))), transfer)))
                .hasStackTraceContaining("reversal_not_exact");

        assertThat(ledger.balanceOf(bob.id())).contains(Money.of(400, "KZT"));
    }

    private UUID reverse(UUID original) {
        return ledger.post(new PostingRequest(TransactionType.REVERSAL, "undo", List.of(
                PostingLine.credit(alice.id(), Money.of(400, "KZT")),
                PostingLine.debit(bob.id(), Money.of(400, "KZT"))), original));
    }
}