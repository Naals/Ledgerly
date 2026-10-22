package com.project.ledgercore.transfer;

import java.util.UUID;

public record TransferResponse(
        UUID transactionId, UUID fromAccountId, UUID toAccountId, long amountMinor, String currency) {
}
