package com.project.paymentservice.support;

import com.project.paymentservice.ledger.LedgerGateway;
import com.project.paymentservice.ledger.LedgerResult;
import com.project.paymentservice.payment.Payment;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Test double for the ledger: answers however the test says, and remembers what it was asked. */
@Component
@Primary
public class FakeLedgerGateway implements LedgerGateway {

    private static final Function<Payment, LedgerResult> SUCCEEDS =
            payment -> new LedgerResult.Completed(UUID.randomUUID());

    private final List<Payment> calls = new CopyOnWriteArrayList<>();
    private volatile Function<Payment, LedgerResult> behaviour = SUCCEEDS;

    @Override
    public LedgerResult transfer(Payment payment) {
        calls.add(payment);
        return behaviour.apply(payment);
    }

    public void respondWith(Function<Payment, LedgerResult> behaviour) {
        this.behaviour = behaviour;
    }

    public List<Payment> calls() {
        return List.copyOf(calls);
    }

    public void reset() {
        calls.clear();
        behaviour = SUCCEEDS;
    }
}