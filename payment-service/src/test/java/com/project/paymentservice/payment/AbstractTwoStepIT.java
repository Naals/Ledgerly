package com.project.paymentservice.payment;

import com.project.paymentservice.ledger.HoldInfo;
import com.project.paymentservice.ledger.HoldState;
import com.project.paymentservice.support.AbstractIntegrationTest;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

abstract class AbstractTwoStepIT extends AbstractIntegrationTest {

    @Autowired
    PaymentService payments;

    @Autowired
    JdbcTemplate jdbc;

    static CreatePayment manual() {
        return new CreatePayment(UUID.randomUUID(), UUID.randomUUID(), 100_000, "KZT", CaptureMode.MANUAL);
    }

    static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    /** A two-step payment the fake ledger has authorized; the fake is reset afterwards so a test sees only its own calls. */
    Payment authorized() {
        Payment payment = payments.create(newKey(), manual()).payment();
        ledger.reset();
        return payment;
    }

    static HoldInfo hold(Payment payment, HoldState state, UUID transaction) {
        return new HoldInfo(payment.ledgerHoldId(), state, OffsetDateTime.now().plusHours(1), transaction);
    }
}