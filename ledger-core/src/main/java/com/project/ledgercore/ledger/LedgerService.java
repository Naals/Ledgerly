package com.project.ledgercore.ledger;

import com.project.ledgercore.common.Money;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {

    private final LedgerRepository ledger;

    public LedgerService(LedgerRepository ledger) {
        this.ledger = ledger;
    }

    /** Records every line of the request atomically: either all lines are stored or none is. */
    @Transactional
    public UUID post(PostingRequest request) {
        UUID transactionId = ledger.insertTransaction(request.type(), request.description());
        for (PostingLine line : request.lines()) {
            ledger.insertEntry(transactionId, line.accountId(), line.amount());
        }
        return transactionId;
    }

    @Transactional(readOnly = true)
    public Optional<Money> balanceOf(UUID accountId) {
        return ledger.balanceOf(accountId);
    }
}
