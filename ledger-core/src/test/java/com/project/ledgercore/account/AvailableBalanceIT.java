package com.project.ledgercore.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.BalanceSummary;
import com.project.ledgercore.ledger.LedgerService;
import com.project.ledgercore.reversal.ReversalService;
import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.support.LedgerFixtures;
import com.project.ledgercore.transfer.TransferService;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class AvailableBalanceIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountRepository accounts;

    @Autowired
    CashService cash;

    @Autowired
    TransferService transfers;

    @Autowired
    ReversalService reversals;

    @Autowired
    LedgerService ledger;

    @Autowired
    LedgerFixtures fixtures;

    private Account alice;
    private Account bob;

    @BeforeEach
    void openAccounts() {
        alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        bob = accounts.create(NewAccount.customer("bob-" + UUID.randomUUID(), KZT));
        cash.deposit(alice.id(), Money.of(1_000, "KZT"));
        fixtures.insertHold(alice.id(), bob.id(), 300, "KZT", 3_600);
    }

    @Test
    void aHoldLowersWhatIsAvailableButNotTheBalance() {
        BalanceSummary summary = ledger.balanceSummaryOf(alice.id()).orElseThrow();

        assertThat(summary.balance()).isEqualTo(Money.of(1_000, "KZT"));
        assertThat(summary.held()).isEqualTo(Money.of(300, "KZT"));
        assertThat(summary.available()).isEqualTo(Money.of(700, "KZT"));
    }

    @Test
    void aTransferCannotSpendHeldFunds() {
        assertThatThrownBy(() -> transfers.transfer(alice.id(), bob.id(), Money.of(701, "KZT")))
                .isInstanceOf(InsufficientFundsException.class);

        transfers.transfer(alice.id(), bob.id(), Money.of(700, "KZT"));

        assertThat(ledger.balanceOf(alice.id()).orElseThrow()).isEqualTo(Money.of(300, "KZT"));
    }

    @Test
    void aWithdrawalCannotSpendHeldFunds() {
        assertThatThrownBy(() -> cash.withdraw(alice.id(), Money.of(701, "KZT")))
                .isInstanceOf(InsufficientFundsException.class);

        cash.withdraw(alice.id(), Money.of(700, "KZT"));
    }

    @Test
    void aReversalCannotTakeBackFundsTheReceiverHasReserved() {
        UUID transfer = transfers.transfer(alice.id(), bob.id(), Money.of(400, "KZT"));
        fixtures.insertHold(bob.id(), alice.id(), 400, "KZT", 3_600);   // bob has reserved all that he received

        assertThatThrownBy(() -> reversals.reverse(transfer, null))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void theBalanceEndpointShowsHeldAndAvailable() throws Exception {
        mvc.perform(get("/api/v1/accounts/{id}/balance", alice.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountMinor").value(1_000))
                .andExpect(jsonPath("$.heldMinor").value(300))
                .andExpect(jsonPath("$.availableMinor").value(700));

        mvc.perform(get("/api/v1/accounts/{id}/balance", alice.id())
                        .param("at", Instant.now().plusSeconds(60).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heldMinor").isEmpty())
                .andExpect(jsonPath("$.availableMinor").isEmpty());
    }
}