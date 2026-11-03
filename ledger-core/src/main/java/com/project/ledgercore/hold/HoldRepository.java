package com.project.ledgercore.hold;

import com.project.ledgercore.common.Money;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL only. Transaction boundaries and lock order belong to {@link HoldService}. */
@Repository
public class HoldRepository {

    private static final String COLUMNS = "id, account_id, to_account_id, amount_minor, currency, status, reference, "
            + "expires_at, (expires_at <= clock_timestamp()) AS expired, captured_minor, ledger_transaction_id, created_at";

    private static final RowMapper<Hold> ROW_MAPPER = (rs, rowNum) -> {
        Currency currency = Currency.getInstance(rs.getString("currency"));
        long capturedMinor = rs.getLong("captured_minor");
        Money captured = rs.wasNull() ? null : new Money(capturedMinor, currency);
        return new Hold(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getObject("to_account_id", UUID.class),
                new Money(rs.getLong("amount_minor"), currency),
                HoldStatus.valueOf(rs.getString("status")),
                rs.getString("reference"),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getBoolean("expired"),
                captured,
                rs.getObject("ledger_transaction_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class));
    };

    private final JdbcClient jdbc;

    public HoldRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates an ACTIVE hold that expires {@code ttl} from now (by the database's clock). */
    public Hold insert(UUID accountId, UUID toAccountId, Money amount, String reference, Duration ttl) {
        return jdbc.sql("INSERT INTO holds (account_id, to_account_id, amount_minor, currency, reference, expires_at) "
                        + "VALUES (:account, :toAccount, :amount, :currency, :reference, "
                        + "clock_timestamp() + (:ttlSeconds * interval '1 second')) RETURNING " + COLUMNS)
                .param("account", accountId)
                .param("toAccount", toAccountId)
                .param("amount", amount.minorUnits())
                .param("currency", amount.currency().getCurrencyCode())
                .param("reference", reference)
                .param("ttlSeconds", ttl.toSeconds())
                .query(ROW_MAPPER)
                .single();
    }

    public Optional<Hold> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM holds WHERE id = :id")
                .param("id", id)
                .query(ROW_MAPPER)
                .optional();
    }

    /** Reads the hold and locks its row until the transaction ends, so nobody else can finish it meanwhile. */
    public Optional<Hold> findByIdForUpdate(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM holds WHERE id = :id FOR NO KEY UPDATE")
                .param("id", id)
                .query(ROW_MAPPER)
                .optional();
    }

    /** ACTIVE to RELEASED or EXPIRED. Returns how many rows changed (0 if it was no longer ACTIVE). */
    public int finish(UUID id, HoldStatus status) {
        if (status != HoldStatus.RELEASED && status != HoldStatus.EXPIRED) {
            throw new IllegalArgumentException("finish() only releases or expires; use markCaptured to capture");
        }
        return jdbc.sql("UPDATE holds SET status = :status WHERE id = :id AND status = 'ACTIVE'")
                .param("status", status.name())
                .param("id", id)
                .update();
    }

    /** ACTIVE to CAPTURED. The database frees the whole hold at this moment, then only {@code capturedMinor} is posted. */
    public int markCaptured(UUID id, long capturedMinor) {
        return jdbc.sql("UPDATE holds SET status = 'CAPTURED', captured_minor = :captured "
                        + "WHERE id = :id AND status = 'ACTIVE'")
                .param("captured", capturedMinor)
                .param("id", id)
                .update();
    }

    public void setLedgerTransaction(UUID id, UUID ledgerTransactionId) {
        jdbc.sql("UPDATE holds SET ledger_transaction_id = :transaction WHERE id = :id")
                .param("transaction", ledgerTransactionId)
                .param("id", id)
                .update();
    }

    /** Up to {@code limit} ACTIVE holds whose time has run out, locked; holds another transaction has locked are skipped. */
    public List<Hold> lockDue(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM holds WHERE status = 'ACTIVE' AND expires_at <= clock_timestamp() "
                        + "ORDER BY expires_at LIMIT :limit FOR NO KEY UPDATE SKIP LOCKED")
                .param("limit", limit)
                .query(ROW_MAPPER)
                .list();
    }
}