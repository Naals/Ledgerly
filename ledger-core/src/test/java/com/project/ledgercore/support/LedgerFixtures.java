package com.project.ledgercore.support;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Raw-SQL helpers for tests that exercise the database schema directly. */
@Component
public class LedgerFixtures {

    private final JdbcTemplate jdbc;

    public LedgerFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID customerAccount(String currency) {
        return insertAccount("CUSTOMER", currency, false);
    }

    /** A bank-owned account such as cash-in-bank; the only kind that may go negative. */
    public UUID systemAccount(String currency) {
        return insertAccount("SYSTEM", currency, true);
    }

    public void insertTransaction(UUID transactionId) {
        jdbc.update("INSERT INTO ledger_transactions (id, type) VALUES (?, 'TRANSFER')", transactionId);
    }

    public void insertEntry(UUID transactionId, UUID accountId, long amount, String currency) {
        jdbc.update("INSERT INTO ledger_entries (transaction_id, account_id, amount, currency) VALUES (?, ?, ?, ?)",
                transactionId, accountId, amount, currency);
    }

    public long balanceOf(UUID accountId) {
        Long balance = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE account_id = ?", Long.class, accountId);
        return balance == null ? 0 : balance;
    }

    private UUID insertAccount(String type, String currency, boolean allowNegative) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, owner_ref, type, currency, allow_negative) VALUES (?, ?, ?, ?, ?)",
                id, "owner-" + id, type, currency, allowNegative);
        return id;
    }
}