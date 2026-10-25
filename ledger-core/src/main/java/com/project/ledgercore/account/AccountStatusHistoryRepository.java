package com.project.ledgercore.account;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountStatusHistoryRepository {

    private final JdbcClient jdbc;

    public AccountStatusHistoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID accountId, AccountStatus from, AccountStatus to, String reason) {
        jdbc.sql("INSERT INTO account_status_events (account_id, from_status, to_status, reason) "
                        + "VALUES (:accountId, :fromStatus, :toStatus, :reason)")
                .param("accountId", accountId)
                .param("fromStatus", from.name())
                .param("toStatus", to.name())
                .param("reason", reason)
                .update();
    }

    /** The most recent changes first. */
    public List<AccountStatusEvent> findRecent(UUID accountId, int limit) {
        return jdbc.sql("SELECT id, from_status, to_status, reason, created_at FROM account_status_events "
                        + "WHERE account_id = :accountId ORDER BY id DESC LIMIT :limit")
                .param("accountId", accountId)
                .param("limit", limit)
                .query((rs, rowNum) -> new AccountStatusEvent(
                        rs.getLong("id"),
                        AccountStatus.valueOf(rs.getString("from_status")),
                        AccountStatus.valueOf(rs.getString("to_status")),
                        rs.getString("reason"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }
}