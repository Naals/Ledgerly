package com.project.ledgercore.ledger;

import com.project.ledgercore.common.Money;
import java.util.UUID;

public record TransactionEntryView(long id, UUID accountId, Money amount) {
}
