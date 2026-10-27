package com.project.ledgercore.reversal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.ledger.LedgerService;
import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.transfer.TransferService;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class ReversalApiIT extends AbstractIntegrationTest {

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
    JdbcTemplate jdbc;

    private Account alice;
    private Account bob;
    private UUID transfer;

    @BeforeEach
    void postATransfer() {
        alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        bob = accounts.create(NewAccount.customer("bob-" + UUID.randomUUID(), KZT));
        cash.deposit(alice.id(), Money.of(1_000, "KZT"));
        transfer = transfers.transfer(alice.id(), bob.id(), Money.of(400, "KZT"));
    }

    @Test
    void reversingATransferRestoresBothBalances() throws Exception {
        mvc.perform(reversal(transfer, UUID.randomUUID().toString(), "{\"reason\": \"wrong account\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.reversalTransactionId").isNotEmpty())
                .andExpect(jsonPath("$.reversesTransactionId").value(transfer.toString()));

        assertThat(ledger.balanceOf(alice.id())).contains(Money.of(1_000, "KZT"));
        assertThat(ledger.balanceOf(bob.id())).contains(Money.zero(KZT));
    }

    @Test
    void aTransactionCanBeReversedOnlyOnce() throws Exception {
        mvc.perform(reversal(transfer, UUID.randomUUID().toString(), null)).andExpect(status().isCreated());

        mvc.perform(reversal(transfer, UUID.randomUUID().toString(), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:already-reversed"));
    }

    @Test
    void aRetryWithTheSameKeyReplaysTheReversal() throws Exception {
        String key = UUID.randomUUID().toString();

        String first = mvc.perform(reversal(transfer, key, null))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(reversal(transfer, key, null))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(second, "$.reversalTransactionId"))
                .isEqualTo(JsonPath.read(first, "$.reversalTransactionId"));
        assertThat(ledger.balanceOf(bob.id())).contains(Money.zero(KZT));
    }

    @Test
    void aReversalCannotBeReversed() throws Exception {
        UUID reversal = reversals.reverse(transfer, null);

        mvc.perform(reversal(reversal, UUID.randomUUID().toString(), null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:cannot-reverse-a-reversal"));
    }

    @Test
    void aReversalNeedsTheMoneyToStillBeThere() throws Exception {
        cash.withdraw(bob.id(), Money.of(300, "KZT"));

        mvc.perform(reversal(transfer, UUID.randomUUID().toString(), null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:insufficient-funds"));

        assertThat(ledger.balanceOf(alice.id())).contains(Money.of(600, "KZT"));
    }

    @Test
    void anUnknownTransactionIs404() throws Exception {
        mvc.perform(reversal(UUID.randomUUID(), UUID.randomUUID().toString(), null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:transaction-not-found"));
    }

    @Test
    void aFrozenAccountBlocksTheReversal() throws Exception {
        jdbc.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?", bob.id());

        mvc.perform(reversal(transfer, UUID.randomUUID().toString(), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ledgerly:problem:account-not-active"));
    }

    private static MockHttpServletRequestBuilder reversal(UUID transactionId, String key, String body) {
        MockHttpServletRequestBuilder request = post("/api/v1/transactions/{id}/reversals", transactionId)
                .header("Idempotency-Key", key);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request;
    }
}