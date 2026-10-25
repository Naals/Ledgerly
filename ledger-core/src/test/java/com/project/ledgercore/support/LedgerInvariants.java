package com.project.ledgercore.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Checks that must hold after any amount of concurrent activity. */
@Component
public class LedgerInvariants {

    private final JdbcTemplate jdbc;

    public LedgerInvariants(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every customer account's cached balance equals the sum of its entries. */
    public void assertBalanceCacheMatchesEntries() {
        List<String> drifting = jdbc.queryForList("""
                SELECT a.id::text FROM accounts a
                 WHERE NOT a.allow_negative
                   AND a.balance <> COALESCE(
                           (SELECT SUM(e.amount) FROM ledger_entries e WHERE e.account_id = a.id), 0)
                """, String.class);

        assertThat(drifting)
                .as("accounts whose cached balance differs from the sum of their entries")
                .isEmpty();
    }

    /**
     * For every customer account: entry numbers are contiguous from 1, every running balance equals the
     * previous one plus the entry's amount, the last running balance is the account's balance, and
     * posting times never go backwards.
     */
    public void assertEntryStampsAreConsistent() {
        List<String> broken = jdbc.queryForList("""
                SELECT a.id::text FROM accounts a
                 WHERE NOT a.allow_negative AND (
                        a.last_entry_seq <> COALESCE(
                            (SELECT MAX(e.account_seq) FROM ledger_entries e WHERE e.account_id = a.id), 0)
                     OR a.last_entry_seq <> (SELECT COUNT(*) FROM ledger_entries e WHERE e.account_id = a.id)
                     OR a.balance <> COALESCE(
                            (SELECT e.balance_after FROM ledger_entries e
                              WHERE e.account_id = a.id ORDER BY e.account_seq DESC LIMIT 1), 0))
                UNION
                SELECT x.account_id::text FROM (
                     SELECT e.account_id, e.amount, e.balance_after, e.posted_at,
                            LAG(e.balance_after, 1, 0::bigint) OVER w AS prev_balance,
                            LAG(e.posted_at) OVER w AS prev_posted
                       FROM ledger_entries e
                       JOIN accounts a ON a.id = e.account_id AND NOT a.allow_negative
                     WINDOW w AS (PARTITION BY e.account_id ORDER BY e.account_seq)) x
                 WHERE x.prev_balance + x.amount <> x.balance_after OR x.posted_at < x.prev_posted
                """, String.class);

        assertThat(broken)
                .as("accounts whose entry numbers, running balances or posting times are inconsistent")
                .isEmpty();
    }
}