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

    public UUID insertTransaction(TransactionType type, String description, UUID reversesTransactionId) {
        return jdbc.sql("INSERT INTO ledger_transactions (type, description, reverses_transaction_id) "
                        + "VALUES (:type, :description, :reverses) RETURNING id")
                .param("type", type.name())
                .param("description", description)
                .param("reverses", reversesTransactionId)
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

    /**
     * The balance of an account; empty if the account does not exist. Customer accounts read their cached
     * balance in constant time. The bank's own accounts, which are not cached, are summed from their entries.
     */
    public Optional<Money> balanceOf(UUID accountId) {
        return jdbc.sql("SELECT a.currency, "
                        + "CASE WHEN a.allow_negative "
                        + "THEN (SELECT COALESCE(SUM(e.amount), 0) FROM ledger_entries e WHERE e.account_id = a.id) "
                        + "ELSE a.balance END AS balance "
                        + "FROM accounts a WHERE a.id = :accountId")
                .param("accountId", accountId)
                .query((rs, rowNum) -> new Money(
                        rs.getLong("balance"),
                        Currency.getInstance(rs.getString("currency"))))
                .optional();
    }

    /**
     * A customer account's entries, newest first, by the account's own entry number.
     * {@code beforeSequence} is an exclusive keyset cursor (null = first page).
     */
    public List<EntryView> findEntries(UUID accountId, Long beforeSequence, int limit) {
        String sql = "SELECT e.id, e.account_seq, e.balance_after, e.transaction_id, t.type, t.description, "
                + "e.amount, e.currency, e.posted_at "
                + "FROM ledger_entries e JOIN ledger_transactions t ON t.id = e.transaction_id "
                + "WHERE e.account_id = :accountId AND e.account_seq IS NOT NULL "
                + (beforeSequence == null ? "" : "AND e.account_seq < :beforeSequence ")
                + "ORDER BY e.account_seq DESC LIMIT :limit";

        JdbcClient.StatementSpec statement = jdbc.sql(sql)
                .param("accountId", accountId)
                .param("limit", limit);
        if (beforeSequence != null) {
            statement = statement.param("beforeSequence", beforeSequence);
        }

        return statement.query((rs, rowNum) -> {
            Currency currency = Currency.getInstance(rs.getString("currency"));
            return new EntryView(
                    rs.getLong("id"),
                    rs.getLong("account_seq"),
                    rs.getObject("transaction_id", UUID.class),
                    TransactionType.valueOf(rs.getString("type")),
                    rs.getString("description"),
                    new Money(rs.getLong("amount"), currency),
                    new Money(rs.getLong("balance_after"), currency),
                    rs.getObject("posted_at", OffsetDateTime.class));
        }).list();
    }

    public Optional<TransactionView> findTransaction(UUID transactionId) {
        return jdbc.sql("SELECT id, type, description, created_at FROM ledger_transactions WHERE id = :id")
                .param("id", transactionId)
                .query((rs, rowNum) -> new TransactionView(
                        rs.getObject("id", UUID.class),
                        TransactionType.valueOf(rs.getString("type")),
                        rs.getString("description"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        List.of()))
                .optional()
                .map(header -> new TransactionView(
                        header.id(), header.type(), header.description(), header.createdAt(),
                        findTransactionEntries(transactionId)));
    }

    private List<TransactionEntryView> findTransactionEntries(UUID transactionId) {
        return jdbc.sql("SELECT id, account_id, amount, currency FROM ledger_entries "
                        + "WHERE transaction_id = :id ORDER BY id")
                .param("id", transactionId)
                .query((rs, rowNum) -> new TransactionEntryView(
                        rs.getLong("id"),
                        rs.getObject("account_id", UUID.class),
                        new Money(rs.getLong("amount"), Currency.getInstance(rs.getString("currency")))))
                .list();
    }

    /**
     * The balance of a customer account at a moment in time: the running balance after the last entry
     * posted at or before it, or zero if there was none. Empty if the account does not exist.
     */
    public Optional<Money> balanceAt(UUID accountId, OffsetDateTime at) {
        return jdbc.sql("SELECT a.currency, COALESCE((SELECT e.balance_after FROM ledger_entries e "
                        + "WHERE e.account_id = a.id AND e.account_seq IS NOT NULL AND e.posted_at <= :at "
                        + "ORDER BY e.posted_at DESC, e.account_seq DESC LIMIT 1), 0) AS balance "
                        + "FROM accounts a WHERE a.id = :accountId")
                .param("accountId", accountId)
                .param("at", at)
                .query((rs, rowNum) -> new Money(
                        rs.getLong("balance"),
                        Currency.getInstance(rs.getString("currency"))))
                .optional();
    }

    /** The id of the reversal of a transaction, if it has been reversed. */
    public Optional<UUID> findReversalOf(UUID transactionId) {
        return jdbc.sql("SELECT id FROM ledger_transactions WHERE reverses_transaction_id = :id")
                .param("id", transactionId)
                .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                .optional();
    }
}