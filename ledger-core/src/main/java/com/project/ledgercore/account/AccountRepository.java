package com.project.ledgercore.account;

import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private static final String COLUMNS = "id, owner_ref, type, currency, status, allow_negative, created_at";

    private static final RowMapper<Account> ROW_MAPPER = (rs, rowNum) -> new Account(
            rs.getObject("id", UUID.class),
            rs.getString("owner_ref"),
            AccountType.valueOf(rs.getString("type")),
            Currency.getInstance(rs.getString("currency")),
            AccountStatus.valueOf(rs.getString("status")),
            rs.getBoolean("allow_negative"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Account create(NewAccount account) {
        return jdbc.sql("INSERT INTO accounts (owner_ref, type, currency, allow_negative) "
                        + "VALUES (:ownerRef, :type, :currency, :allowNegative) "
                        + "RETURNING " + COLUMNS)
                .param("ownerRef", account.ownerRef())
                .param("type", account.type().name())
                .param("currency", account.currency().getCurrencyCode())
                .param("allowNegative", account.allowNegative())
                .query(ROW_MAPPER)
                .single();
    }

    public Optional<Account> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id = :id")
                .param("id", id)
                .query(ROW_MAPPER)
                .optional();
    }


    public Account findOrCreateSystem(String reference, Currency currency) {
        jdbc.sql("INSERT INTO accounts (owner_ref, type, currency, allow_negative) "
                        + "VALUES (:reference, 'SYSTEM', :currency, TRUE) "
                        + "ON CONFLICT (owner_ref, currency) WHERE type = 'SYSTEM' DO NOTHING")
                .param("reference", reference)
                .param("currency", currency.getCurrencyCode())
                .update();
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts "
                        + "WHERE owner_ref = :reference AND currency = :currency AND type = 'SYSTEM'")
                .param("reference", reference)
                .param("currency", currency.getCurrencyCode())
                .query(ROW_MAPPER)
                .single();
    }
}