package com.project.ledgercore.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.common.BusinessRuleException;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.LedgerService;
import com.project.ledgercore.ledger.PostingLine;
import com.project.ledgercore.ledger.PostingRequest;
import com.project.ledgercore.ledger.TransactionType;
import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.support.ConcurrentRunner;
import com.project.ledgercore.support.LedgerInvariants;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class EntryStampIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LedgerService ledger;

    @Autowired
    AccountRepository accounts;

    @Autowired
    LedgerInvariants invariants;

    private Account funding;
    private Account alice;

    @BeforeEach
    void openAccounts() {
        funding = accounts.create(NewAccount.system("stamp-funding-" + UUID.randomUUID(), KZT));
        alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
    }

    @Test
    void entriesGetGapFreeNumbersAndARunningBalance() {
        deposit(100);
        deposit(200);
        withdraw(50);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT account_seq, balance_after FROM ledger_entries WHERE account_id = ? ORDER BY account_seq",
                alice.id());

        assertThat(rows).extracting(row -> row.get("account_seq")).containsExactly(1L, 2L, 3L);
        assertThat(rows).extracting(row -> row.get("balance_after")).containsExactly(100L, 300L, 250L);
    }

    @Test
    void aRolledBackPostingLeavesNoGapInTheNumbers() {
        deposit(100);   // entry number 1

        assertThatThrownBy(() -> withdraw(500)).isInstanceOf(BusinessRuleException.class);   // overdraft, refused

        deposit(40);    // must be number 2, not 3

        List<Long> numbers = jdbc.queryForList(
                "SELECT account_seq FROM ledger_entries WHERE account_id = ? ORDER BY account_seq",
                Long.class, alice.id());
        assertThat(numbers).containsExactly(1L, 2L);
    }

    @Test
    void theBanksOwnAccountsAreNotStamped() {
        deposit(100);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT account_seq, balance_after, posted_at FROM ledger_entries WHERE account_id = ?",
                funding.id());

        assertThat(row.values()).containsOnlyNulls();
    }

    @Test
    void concurrentPostingsToOneAccountKeepTheNumbersContiguousAndTheRunningBalanceExact() throws Exception {
        List<Callable<Boolean>> postings = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            long amount = 1 + (i % 7);
            postings.add(() -> {
                deposit(amount);
                return true;
            });
        }

        ConcurrentRunner.runAll(8, postings);

        invariants.assertEntryStampsAreConsistent();
        Long entries = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE account_id = ?", Long.class, alice.id());
        Long lastNumber = jdbc.queryForObject(
                "SELECT last_entry_seq FROM accounts WHERE id = ?", Long.class, alice.id());
        assertThat(entries).isEqualTo(200);
        assertThat(lastNumber).isEqualTo(200);
    }

    private void deposit(long amount) {
        ledger.post(new PostingRequest(TransactionType.DEPOSIT, "stamp test", List.of(
                PostingLine.debit(funding.id(), Money.of(amount, "KZT")),
                PostingLine.credit(alice.id(), Money.of(amount, "KZT")))));
    }

    private void withdraw(long amount) {
        ledger.post(new PostingRequest(TransactionType.WITHDRAWAL, "stamp test", List.of(
                PostingLine.debit(alice.id(), Money.of(amount, "KZT")),
                PostingLine.credit(funding.id(), Money.of(amount, "KZT")))));
    }
}