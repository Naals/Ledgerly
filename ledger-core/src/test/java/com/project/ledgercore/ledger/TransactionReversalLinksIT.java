package com.project.ledgercore.ledger;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.reversal.ReversalService;
import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.transfer.TransferService;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class TransactionReversalLinksIT extends AbstractIntegrationTest {

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

    private UUID transfer;
    private UUID reversal;

    @BeforeEach
    void transferAndReverse() {
        Account alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        Account bob = accounts.create(NewAccount.customer("bob-" + UUID.randomUUID(), KZT));
        cash.deposit(alice.id(), Money.of(1_000, "KZT"));
        transfer = transfers.transfer(alice.id(), bob.id(), Money.of(400, "KZT"));
        reversal = reversals.reverse(transfer, "test");
    }

    @Test
    void aReversedTransactionPointsToItsReversal() throws Exception {
        mvc.perform(get("/api/v1/transactions/{id}", transfer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("TRANSFER"))
                .andExpect(jsonPath("$.reversedByTransactionId").value(reversal.toString()))
                .andExpect(jsonPath("$.reversesTransactionId").value(nullValue()));
    }

    @Test
    void aReversalPointsToItsOriginal() throws Exception {
        mvc.perform(get("/api/v1/transactions/{id}", reversal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("REVERSAL"))
                .andExpect(jsonPath("$.reversesTransactionId").value(transfer.toString()))
                .andExpect(jsonPath("$.reversedByTransactionId").value(nullValue()))
                .andExpect(jsonPath("$.entries[*].amountMinor", containsInAnyOrder(400, -400)));
    }
}