package com.project.ledgercore.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.support.AbstractIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class AccountsSchemaIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void newAccountIsActiveAndCannotGoNegativeByDefault() {
        UUID id = insertAccount("CUSTOMER", "KZT", false);

        Map<String, Object> row = jdbc.queryForMap("SELECT status, allow_negative FROM accounts WHERE id = ?", id);

        assertThat(row).containsEntry("status", "ACTIVE").containsEntry("allow_negative", false);
    }

    @Test
    void currencyMustBeThreeUppercaseLetters() {
        assertThatThrownBy(() -> insertAccount("CUSTOMER", "kzt", false))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAccount("CUSTOMER", "K1Z", false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unknownAccountTypeIsRejected() {
        assertThatThrownBy(() -> insertAccount("ROBOT", "KZT", false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlySystemAccountsMayAllowNegativeBalance() {
        assertThatThrownBy(() -> insertAccount("CUSTOMER", "KZT", true))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insertAccount("SYSTEM", "KZT", true)).doesNotThrowAnyException();
    }

    private UUID insertAccount(String type, String currency, boolean allowNegative) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, owner_ref, type, currency, allow_negative) VALUES (?, ?, ?, ?, ?)",
                id, "owner-" + id, type, currency, allowNegative);
        return id;
    }
}
