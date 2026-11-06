package com.project.paymentservice.events;

import com.project.paymentservice.ledger.HoldState;
import java.util.Optional;
import java.util.UUID;

/**
 * The part of a ledger HoldStatusChanged event that this service cares about.
 *
 * @param status               the hold's new state, as the ledger names it (see {@link HoldState})
 * @param reference            the reference the hold was placed with: this service sends the payment id
 * @param ledgerTransactionId  the transfer that captured the hold; present exactly when the hold was captured
 */
public record HoldEvent(int version, UUID holdId, String status, String previousStatus, String reference,
                        UUID ledgerTransactionId) {

    public HoldState state() {
        return HoldState.valueOf(status);
    }

    /** The payment this hold belongs to; empty if the reference is missing or is not a payment id (someone else's hold). */
    public Optional<UUID> paymentId() {
        if (reference == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(reference));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}