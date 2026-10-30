package com.project.paymentservice.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.paymentservice.ledger.LedgerResult;
import com.project.paymentservice.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class PaymentExecutionIT extends AbstractIntegrationTest {

    @Autowired
    PaymentService payments;

    @Autowired
    MockMvc mvc;

    private static CreatePayment command() {
        return new CreatePayment(UUID.randomUUID(), UUID.randomUUID(), 100_000, "KZT");
    }

    private static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    @Test
    void aTransferTheLedgerPostsCompletesThePayment() {
        UUID transactionId = UUID.randomUUID();
        ledger.respondWith(payment -> new LedgerResult.Completed(transactionId));

        Payment payment = payments.create(newKey(), command()).payment();

        assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.ledgerTransactionId()).isEqualTo(transactionId);
        assertThat(ledger.calls()).hasSize(1);
    }

    @Test
    void aFinalRefusalFailsThePaymentWithTheLedgersCode() {
        ledger.respondWith(payment -> new LedgerResult.Rejected("insufficient-funds"));

        Payment payment = payments.create(newKey(), command()).payment();

        assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.failureCode()).isEqualTo("insufficient-funds");
        assertThat(payment.ledgerTransactionId()).isNull();
    }

    @Test
    void anUnavailableLedgerLeavesThePaymentPendingAndCountsTheAttempt() {
        ledger.respondWith(payment -> new LedgerResult.Unavailable("connection refused"));

        Payment payment = payments.create(newKey(), command()).payment();

        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.attempts()).isEqualTo(1);
    }

    @Test
    void aReplayOfACompletedPaymentDoesNotCallTheLedgerAgain() {
        String key = newKey();
        CreatePayment command = command();
        payments.create(key, command);

        CreatedPayment replay = payments.create(key, command);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.payment().status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(ledger.calls()).hasSize(1);
    }

    @Test
    void aReplayOfAPendingPaymentAsksTheLedgerAgainWithTheSameIdempotencyKey() {
        String key = newKey();
        CreatePayment command = command();
        ledger.respondWith(payment -> new LedgerResult.Unavailable("down"));
        CreatedPayment first = payments.create(key, command);
        assertThat(first.payment().status()).isEqualTo(PaymentStatus.PENDING);

        ledger.respondWith(payment -> new LedgerResult.Completed(UUID.randomUUID()));
        CreatedPayment second = payments.create(key, command);

        assertThat(second.replayed()).isTrue();
        assertThat(second.payment().status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(ledger.calls()).extracting(Payment::id).containsExactly(first.payment().id(), first.payment().id());
    }

    @Test
    void theApiReportsAFailedPaymentAsACreatedResourceWithItsCode() throws Exception {
        ledger.respondWith(payment -> new LedgerResult.Rejected("account-not-active"));

        mvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromAccountId": "%s", "toAccountId": "%s", "amountMinor": 500, "currency": "KZT"}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("account-not-active"));
    }
}