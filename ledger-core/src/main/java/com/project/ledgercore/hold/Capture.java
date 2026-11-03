package com.project.ledgercore.hold;

import com.project.ledgercore.common.Money;
import java.util.UUID;

/** The outcome of a capture: the ledger transaction that moved the money, and how much it moved. */
public record Capture(UUID holdId, UUID transactionId, Money captured) {
}