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
}