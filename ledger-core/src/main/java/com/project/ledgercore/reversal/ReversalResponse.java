package com.project.ledgercore.reversal;

import java.util.UUID;

public record ReversalResponse(UUID reversalTransactionId, UUID reversesTransactionId) {
}