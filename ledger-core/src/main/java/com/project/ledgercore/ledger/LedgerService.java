package com.project.ledgercore.ledger;

import com.project.ledgercore.common.BadRequestException;
import com.project.ledgercore.common.LockOrder;
import com.project.ledgercore.common.Money;

import java.util.Comparator;
import java.util.List;
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
        // The balance trigger locks each customer account row as its entry is inserted. Inserting in
        // ascending account id keeps that lock order identical in every transaction (ADR 0004).
        List<PostingLine> ordered = request.lines().stream()
                .sorted(Comparator.comparing(PostingLine::accountId, LockOrder.BY_ID))
                .toList();
        for (PostingLine line : ordered) {
            ledger.insertEntry(transactionId, line.accountId(), line.amount());
        }
        return transactionId;
    }

    @Transactional(readOnly = true)
    public Optional<Money> balanceOf(UUID accountId) {
        return ledger.balanceOf(accountId);
    }

    public static final int MAX_PAGE_SIZE = 200;

    /** One page of an account's entries, newest first. Pass the returned cursor as {@code beforeId} for the next page. */
    @Transactional(readOnly = true)
    public EntryPage entriesOf(UUID accountId, Long beforeId, int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new BadRequestException("invalid-limit", "limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (beforeId != null && beforeId < 1) {
            throw new BadRequestException("invalid-cursor", "before must be a positive entry id");
        }

        // fetch one extra row: if it exists, there is a next page
        List<EntryView> rows = ledger.findEntries(accountId, beforeId, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<EntryView> items = hasMore ? rows.subList(0, limit) : rows;
        Long nextBefore = hasMore ? items.get(items.size() - 1).id() : null;
        return new EntryPage(List.copyOf(items), nextBefore);
    }

    @Transactional(readOnly = true)
    public TransactionView transaction(UUID transactionId) {
        return ledger.findTransaction(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    }

}
