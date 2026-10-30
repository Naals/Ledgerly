package com.project.paymentservice.events;

import java.util.UUID;

/** The part of a ledger TransactionPosted event that this service cares about. */
public record LedgerEvent(int version, UUID transactionId, String type, UUID reversesTransactionId) {

    public boolean isReversal() {
        return "REVERSAL".equals(type);
    }
}