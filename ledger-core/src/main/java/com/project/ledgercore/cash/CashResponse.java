package com.project.ledgercore.cash;


import java.util.UUID;

public record CashResponse(UUID transactionId, UUID accountId, String type, long amountMinor, String currency) {
}