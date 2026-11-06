package com.project.ledgercore.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.support.LedgerFixtures;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class HoldEventsIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    HoldService holds;

    @Autowired
    AccountRepository accounts;

    @Autowired
    CashService cash;

    @Autowired
    LedgerFixtures fixtures;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    private Account alice;
    private Account bob;

    @BeforeEach
    void openAccounts() {
        alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        bob = accounts.create(NewAccount.customer("bob-" + UUID.randomUUID(), KZT));
        cash.deposit(alice.id(), Money.of(1_000, "KZT"));
    }

    @Test
    void placingAHoldEmitsNothing() {
        Hold hold = holds.place(alice.id(), bob.id(), Money.of(300, "KZT"), "payment-42", null);

        assertThat(eventsOf(hold.id())).isEmpty();
    }

    @Test
    void releasingAHoldEmitsOneReleasedEvent() {
        Hold hold = holds.place(alice.id(), bob.id(), Money.of(300, "KZT"), "payment-42", null);
        holds.release(hold.id());

        List<String> events = eventsOf(hold.id());

        assertThat(events).hasSize(1);
        assertThat(JsonPath.<String>read(events.get(0), "$.status")).isEqualTo("RELEASED");
        assertThat(JsonPath.<String>read(events.get(0), "$.previousStatus")).isEqualTo("ACTIVE");
        assertThat(JsonPath.<String>read(events.get(0), "$.reference")).isEqualTo("payment-42");
        assertThat(JsonPath.<Object>read(events.get(0), "$.ledgerTransactionId")).isNull();
    }

    @Test
    void aCaptureEmitsOneEventThatNamesTheTransferAndWhatWasCaptured() {
        Hold hold = holds.place(alice.id(), bob.id(), Money.of(300, "KZT"), "payment-42", null);
        Capture capture = holds.capture(hold.id(), 120L);

        List<String> events = eventsOf(hold.id());

        assertThat(events).hasSize(1);
        assertThat(JsonPath.<String>read(events.get(0), "$.status")).isEqualTo("CAPTURED");
        assertThat(JsonPath.<Integer>read(events.get(0), "$.capturedMinor")).isEqualTo(120);
        assertThat(JsonPath.<Integer>read(events.get(0), "$.amountMinor")).isEqualTo(300);
        // the transfer is recorded on the hold AFTER the status change; the event still carries it
        assertThat(JsonPath.<String>read(events.get(0), "$.ledgerTransactionId")).isEqualTo(capture.transactionId().toString());
    }

    @Test
    void anExpiredHoldEmitsOneExpiredEvent() {
        UUID hold = fixtures.insertHold(alice.id(), bob.id(), 300, "KZT", -5);

        holds.expireDue(100);

        List<String> events = eventsOf(hold);
        assertThat(events).hasSize(1);
        assertThat(JsonPath.<String>read(events.get(0), "$.status")).isEqualTo("EXPIRED");
    }

    @Test
    void aCaptureThatRollsBackLeavesNoEventAndTheHoldActive() {
        Hold hold = holds.place(alice.id(), bob.id(), Money.of(300, "KZT"), null, null);

        tx.executeWithoutResult(status -> {
            holds.capture(hold.id(), null);
            status.setRollbackOnly();
        });

        assertThat(eventsOf(hold.id())).isEmpty();
        assertThat(holds.get(hold.id()).status()).isEqualTo(HoldStatus.ACTIVE);
    }

    @Test
    void aRefusedCaptureLeavesNoEvent() {
        UUID hold = fixtures.insertHold(alice.id(), bob.id(), 300, "KZT", -5);

        assertThatThrownBy(() -> holds.capture(hold, null)).isInstanceOf(HoldExpiredException.class);

        assertThat(eventsOf(hold)).isEmpty();
    }

    private List<String> eventsOf(UUID holdId) {
        return jdbc.queryForList("SELECT CAST(payload AS text) FROM outbox_events "
                + "WHERE aggregate_id = ? AND event_type = 'HoldStatusChanged' ORDER BY seq", String.class, holdId);
    }
}