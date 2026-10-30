package com.project.paymentservice.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.paymentservice.support.AbstractIntegrationTest;
import com.project.paymentservice.support.ConcurrentRunner;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PaymentServiceIT extends AbstractIntegrationTest {

    @Autowired
    PaymentService payments;

    private static CreatePayment command() {
        return new CreatePayment(UUID.randomUUID(), UUID.randomUUID(), 100_000, "KZT");
    }

    private static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    @Test
    void aNewPaymentIsRegistered() {
        CreatePayment command = command();

        CreatedPayment created = payments.create(newKey(), command);

        assertThat(created.replayed()).isFalse();
        assertThat(created.payment().id()).isNotNull();
        assertThat(created.payment().amountMinor()).isEqualTo(100_000);
        assertThat(created.payment().fromAccountId()).isEqualTo(command.fromAccountId());
    }

    @Test
    void theSameKeyAndRequestReturnTheSamePayment() {
        String key = newKey();
        CreatePayment command = command();

        CreatedPayment first = payments.create(key, command);
        CreatedPayment second = payments.create(key, command);

        assertThat(second.replayed()).isTrue();
        assertThat(second.payment().id()).isEqualTo(first.payment().id());
    }

    @Test
    void theSameKeyWithADifferentRequestIsRefused() {
        String key = newKey();
        CreatePayment original = command();
        payments.create(key, original);

        CreatePayment different = new CreatePayment(
                original.fromAccountId(), original.toAccountId(), original.amountMinor() + 1, "KZT");

        assertThatThrownBy(() -> payments.create(key, different)).isInstanceOf(IdempotencyKeyReuseException.class);
    }

    @Test
    void concurrentCreatesWithOneKeyRegisterOnePayment() throws Exception {
        String key = newKey();
        CreatePayment command = command();
        List<Callable<CreatedPayment>> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            requests.add(() -> payments.create(key, command));
        }

        List<CreatedPayment> results = ConcurrentRunner.runAll(8, requests);

        assertThat(results).extracting(created -> created.payment().id()).containsOnly(results.getFirst().payment().id());
        assertThat(results).filteredOn(created -> !created.replayed()).hasSize(1);
    }
}