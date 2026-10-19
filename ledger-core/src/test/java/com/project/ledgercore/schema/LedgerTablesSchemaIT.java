package com.project.ledgercore.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.support.LedgerFixtures;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

class LedgerTablesSchemaIT extends AbstractIntegrationTest {

    @Autowired
    TransactionTemplate tx;

    @Autowired
    LedgerFixtures fixtures;

    @Test
    void balancedPairOfEntriesIsStored() {
        UUID funding = fixtures.systemAccount("KZT");
        UUID customer = fixtures.customerAccount("KZT");
        UUID txId = UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            fixtures.insertTransaction(txId);
            fixtures.insertEntry(txId, funding, -500, "KZT");
            fixtures.insertEntry(txId, customer, 500, "KZT");
        });

        assertThat(fixtures.balanceOf(customer)).isEqualTo(500);
        assertThat(fixtures.balanceOf(funding)).isEqualTo(-500);
    }

    @Test
    void entryMustReferenceAnExistingTransaction() {
        UUID account = fixtures.customerAccount("KZT");

        assertThatThrownBy(() -> fixtures.insertEntry(UUID.randomUUID(), account, 100, "KZT"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void entryMustReferenceAnExistingAccount() {
        UUID txId = UUID.randomUUID();
        fixtures.insertTransaction(txId);

        assertThatThrownBy(() -> fixtures.insertEntry(txId, UUID.randomUUID(), 100, "KZT"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void entryCurrencyMustMatchAccountCurrency() {
        UUID kztAccount = fixtures.customerAccount("KZT");
        UUID txId = UUID.randomUUID();
        fixtures.insertTransaction(txId);

        assertThatThrownBy(() -> fixtures.insertEntry(txId, kztAccount, 100, "USD"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void zeroAmountEntryIsRejected() {
        UUID account = fixtures.customerAccount("KZT");
        UUID txId = UUID.randomUUID();
        fixtures.insertTransaction(txId);

        assertThatThrownBy(() -> fixtures.insertEntry(txId, account, 0, "KZT"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
