package com.project.ledgercore.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.BadRequestException;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LedgerEntriesIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    LedgerService ledger;

    @Autowired
    AccountRepository accounts;

    @Autowired
    CashService cash;

    @Test
    void pagesThroughEntriesNewestFirst() {
        Account alice = accountWithDeposits(100, 200, 300, 400, 500);

        EntryPage first = ledger.entriesOf(alice.id(), null, 2);
        assertThat(first.items()).extracting(e -> e.amount().minorUnits()).containsExactly(500L, 400L);
        assertThat(first.nextBefore()).isNotNull();

        EntryPage second = ledger.entriesOf(alice.id(), first.nextBefore(), 2);
        assertThat(second.items()).extracting(e -> e.amount().minorUnits()).containsExactly(300L, 200L);
        assertThat(second.nextBefore()).isNotNull();

        EntryPage third = ledger.entriesOf(alice.id(), second.nextBefore(), 2);
        assertThat(third.items()).extracting(e -> e.amount().minorUnits()).containsExactly(100L);
        assertThat(third.nextBefore()).isNull();
    }

    @Test
    void lastPageIsNotFlaggedWhenItFitsExactly() {
        Account alice = accountWithDeposits(100, 200);

        EntryPage page = ledger.entriesOf(alice.id(), null, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextBefore()).isNull();
    }

    @Test
    void entriesCarryTheTransactionTypeAndDescription() {
        Account alice = accountWithDeposits(100);

        EntryView entry = ledger.entriesOf(alice.id(), null, 10).items().get(0);

        assertThat(entry.type()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(entry.description()).isEqualTo("Cash deposit");
        assertThat(entry.amount()).isEqualTo(Money.of(100, "KZT"));
        assertThat(entry.postedAt()).isNotNull();

    }

    @Test
    void invalidLimitsAndCursorsAreRejected() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> ledger.entriesOf(id, null, 0)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ledger.entriesOf(id, null, LedgerService.MAX_PAGE_SIZE + 1))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ledger.entriesOf(id, 0L, 10)).isInstanceOf(BadRequestException.class);
    }

    private Account accountWithDeposits(long... amounts) {
        Account account = accounts.create(NewAccount.customer("statement-" + UUID.randomUUID(), KZT));
        for (long amount : amounts) {
            cash.deposit(account.id(), Money.of(amount, "KZT"));
        }
        return account;
    }
}
