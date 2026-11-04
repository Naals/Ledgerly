package com.project.paymentservice.events;

import java.util.Optional;
import java.util.UUID;

/** The part of a ledger TransactionPosted event that this service cares about. */
public record LedgerEvent(int version, UUID transactionId, String type, String reference, UUID reversesTransactionId) {

    public boolean isReversal() {
        return "REVERSAL".equals(type);
    }

    /**
     * The payment this ledger transaction belongs to. This service sends its payment id as the transfer's
     * reference and the ledger hands the reference back on the transfer's event and on its reversal's, so the
     * payment can be found without knowing which ledger transaction paid it. Empty if the reference is
     * missing or is not a payment id (another client's transaction).
     */
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

    public boolean isTransfer() {
        return "TRANSFER".equals(type);
    }
}