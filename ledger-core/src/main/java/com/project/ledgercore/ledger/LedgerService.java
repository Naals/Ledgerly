package com.project.ledgercore.ledger;

import com.project.ledgercore.common.BadRequestException;
import com.project.ledgercore.common.BusinessRuleException;
import com.project.ledgercore.common.LockOrder;
import com.project.ledgercore.common.Money;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {

    public static final int MAX_PAGE_SIZE = 200;

    private final LedgerRepository ledger;

    public LedgerService(LedgerRepository ledger) {
        this.ledger = ledger;
    }

    /**
     * Records every line of the request atomically: either all lines are stored or none is.
     * A posting that would overdraw a customer account is refused by the database and reported as
     * {@code insufficient-funds}.
     */
    @Transactional
    public UUID post(PostingRequest request) {
        try {
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
        } catch (DataIntegrityViolationException e) {
            if (LedgerConstraints.isOverdraftViolation(e)) {
                throw new BusinessRuleException("insufficient-funds", "The posting would overdraw an account");
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public Optional<Money> balanceOf(UUID accountId) {
        return ledger.balanceOf(accountId);
    }

    /**
     * One page of a customer account's statement, newest first. Entries are numbered per account
     * (1, 2, 3 ...) in the order they were applied, so the number doubles as the paging cursor:
     * pass the returned {@code nextBefore} as {@code beforeSequence} to get the next page.
     */
    @Transactional(readOnly = true)
    public EntryPage entriesOf(UUID accountId, Long beforeSequence, int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new BadRequestException("invalid-limit", "limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (beforeSequence != null && beforeSequence < 1) {
            throw new BadRequestException("invalid-cursor", "before must be a positive entry number");
        }

        // fetch one extra row: if it exists, there is a next page
        List<EntryView> rows = ledger.findEntries(accountId, beforeSequence, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<EntryView> items = hasMore ? rows.subList(0, limit) : rows;
        Long nextBefore = hasMore ? items.get(items.size() - 1).sequence() : null;
        return new EntryPage(List.copyOf(items), nextBefore);
    }

    @Transactional(readOnly = true)
    public TransactionView transaction(UUID transactionId) {
        return ledger.findTransaction(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    }
}