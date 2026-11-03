package com.project.paymentservice.support;

import com.project.paymentservice.ledger.HoldInfo;
import com.project.paymentservice.ledger.HoldState;
import com.project.paymentservice.ledger.LedgerGateway;
import com.project.paymentservice.ledger.LedgerOutcome;
import com.project.paymentservice.ledger.LedgerResult;
import com.project.paymentservice.payment.Payment;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Test double for the ledger: answers however the test says, and remembers what it was asked.
 * Unless a test says otherwise every call succeeds.
 */
@Component
@Primary
public class FakeLedgerGateway implements LedgerGateway {

    private static final Function<Payment, LedgerResult> TRANSFER_SUCCEEDS =
            payment -> new LedgerResult.Completed(UUID.randomUUID());
    private static final Function<Payment, LedgerOutcome<HoldInfo>> AUTHORIZE_SUCCEEDS =
            payment -> new LedgerOutcome.Done<>(new HoldInfo(
                    UUID.randomUUID(), HoldState.ACTIVE, OffsetDateTime.now().plusHours(1), null));
    private static final Function<Payment, LedgerOutcome<UUID>> CAPTURE_SUCCEEDS =
            payment -> new LedgerOutcome.Done<>(UUID.randomUUID());
    private static final Function<Payment, LedgerOutcome<HoldInfo>> RELEASE_SUCCEEDS =
            payment -> new LedgerOutcome.Done<>(new HoldInfo(
                    payment.ledgerHoldId(), HoldState.RELEASED, OffsetDateTime.now().plusHours(1), null));
    private static final Function<UUID, LedgerOutcome<HoldInfo>> HOLD_IS_ACTIVE =
            holdId -> new LedgerOutcome.Done<>(new HoldInfo(
                    holdId, HoldState.ACTIVE, OffsetDateTime.now().plusHours(1), null));

    private final List<Payment> transfers = new CopyOnWriteArrayList<>();
    private final List<String> holdCalls = new CopyOnWriteArrayList<>();
    private volatile Function<Payment, LedgerResult> transferBehaviour = TRANSFER_SUCCEEDS;
    private volatile Function<Payment, LedgerOutcome<HoldInfo>> authorizeBehaviour = AUTHORIZE_SUCCEEDS;
    private volatile Function<Payment, LedgerOutcome<UUID>> captureBehaviour = CAPTURE_SUCCEEDS;
    private volatile Function<Payment, LedgerOutcome<HoldInfo>> releaseBehaviour = RELEASE_SUCCEEDS;
    private volatile Function<UUID, LedgerOutcome<HoldInfo>> holdBehaviour = HOLD_IS_ACTIVE;

    @Override
    public LedgerResult transfer(Payment payment) {
        transfers.add(payment);
        return transferBehaviour.apply(payment);
    }

    @Override
    public LedgerOutcome<HoldInfo> authorize(Payment payment) {
        holdCalls.add("authorize");
        return authorizeBehaviour.apply(payment);
    }

    @Override
    public LedgerOutcome<UUID> capture(Payment payment) {
        holdCalls.add("capture");
        return captureBehaviour.apply(payment);
    }

    @Override
    public LedgerOutcome<HoldInfo> release(Payment payment) {
        holdCalls.add("release");
        return releaseBehaviour.apply(payment);
    }

    @Override
    public LedgerOutcome<HoldInfo> findHold(UUID holdId) {
        holdCalls.add("findHold");
        return holdBehaviour.apply(holdId);
    }

    public void respondWith(Function<Payment, LedgerResult> behaviour) {
        this.transferBehaviour = behaviour;
    }

    public void authorizeWith(Function<Payment, LedgerOutcome<HoldInfo>> behaviour) {
        this.authorizeBehaviour = behaviour;
    }

    public void captureWith(Function<Payment, LedgerOutcome<UUID>> behaviour) {
        this.captureBehaviour = behaviour;
    }

    public void releaseWith(Function<Payment, LedgerOutcome<HoldInfo>> behaviour) {
        this.releaseBehaviour = behaviour;
    }

    public void holdIs(Function<UUID, LedgerOutcome<HoldInfo>> behaviour) {
        this.holdBehaviour = behaviour;
    }

    /** The one-step transfers it was asked for. */
    public List<Payment> calls() {
        return List.copyOf(transfers);
    }

    /** What it was asked to do about holds, in order: "authorize", "capture", "release", "findHold". */
    public List<String> holdCalls() {
        return List.copyOf(holdCalls);
    }

    public void reset() {
        transfers.clear();
        holdCalls.clear();
        transferBehaviour = TRANSFER_SUCCEEDS;
        authorizeBehaviour = AUTHORIZE_SUCCEEDS;
        captureBehaviour = CAPTURE_SUCCEEDS;
        releaseBehaviour = RELEASE_SUCCEEDS;
        holdBehaviour = HOLD_IS_ACTIVE;
    }
}