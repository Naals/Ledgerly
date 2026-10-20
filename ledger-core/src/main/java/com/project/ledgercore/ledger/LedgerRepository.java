package com.project.ledgercore.ledger;

import com.project.ledgercore.common.Money;

import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL only. Transaction boundaries belong to {@link LedgerService}. */
@Repository
public class LedgerRepository {

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insertTransaction(TransactionType type, String description) {
        return jdbc.sql("INSERT INTO ledger_transactions (type, description) "
                        + "VALUES (:type, :description) RETURNING id")
                .param("type", type.name())
                .param("description", description)
                .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                .single();
    }

    public void insertEntry(UUID transactionId, UUID accountId, Money amount) {
        jdbc.sql("INSERT INTO ledger_entries (transaction_id, account_id, amount, currency) "
                        + "VALUES (:transactionId, :accountId, :amount, :currency)")
                .param("transactionId", transactionId)
                .param("accountId", accountId)
                .param("amount", amount.minorUnits())
                .param("currency", amount.currency().getCurrencyCode())
                .update();
    }

    /** The balance is the sum of the account's entries; empty if the account does not exist. */
    public Optional<Money> balanceOf(UUID accountId) {
        return jdbc.sql("SELECT a.currency, COALESCE(SUM(e.amount), 0) AS balance "
                        + "FROM accounts a "
                        + "LEFT JOIN ledger_entries e ON e.account_id = a.id "
                        + "WHERE a.id = :accountId "
                        + "GROUP BY a.currency")
                .param("accountId", accountId)
                .query((rs, rowNum) -> new Money(
                        rs.getLong("balance"),
                        Currency.getInstance(rs.getString("currency"))))
                .optional();
    }

    /** Entries of one account, newest first. {@code beforeId} is an exclusive keyset cursor (null = first page). */
    public List<EntryView> findEntries(UUID accountId, Long beforeId, int limit) {
        String sql = "SELECT e.id, e.transaction_id, t.type, t.description, e.amount, e.currency, e.created_at "
                + "FROM ledger_entries e JOIN ledger_transactions t ON t.id = e.transaction_id "
                + "WHERE e.account_id = :accountId "
                + (beforeId == null ? "" : "AND e.id < :beforeId ")
                + "ORDER BY e.id DESC LIMIT :limit";

        JdbcClient.StatementSpec statement = jdbc.sql(sql)
                .param("accountId", accountId)
                .param("limit", limit);
        if (beforeId != null) {
            statement = statement.param("beforeId", beforeId);
        }

        return statement.query((rs, rowNum) -> new EntryView(
                        rs.getLong("id"),
                        rs.getObject("transaction_id", UUID.class),
                        TransactionType.valueOf(rs.getString("type")),
                        rs.getString("description"),
                        new Money(rs.getLong("amount"), Currency.getInstance(rs.getString("currency"))),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }
}