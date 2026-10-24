package com.project.ledgercore.ledger;

import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A request to record one ledger transaction.
 *
 * <p>The request is validated on construction, so an invalid posting can never reach the database:
 * at least two lines, no zero amounts, and the lines must sum to zero for every currency.
 * (The database enforces the same rule at COMMIT as a second line of defence.)
 */
public record PostingRequest(TransactionType type, String description, List<PostingLine> lines) {

    static final int MAX_DESCRIPTION_LENGTH = 255;

    public PostingRequest {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        lines = List.copyOf(lines);

        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        if (lines.size() < 2) {
            throw new IllegalArgumentException("a ledger transaction needs at least two lines");
        }

        if (lines.stream().map(PostingLine::accountId).distinct().count() != lines.size()) {
            throw new IllegalArgumentException("each account may appear in at most one line");
        }

        Map<Currency, Long> totals = new HashMap<>();
        for (PostingLine line : lines) {
            if (line.amount().isZero()) {
                throw new IllegalArgumentException("line amounts must not be zero");
            }
            totals.merge(line.amount().currency(), line.amount().minorUnits(), Math::addExact);
        }
        totals.forEach((currency, total) -> {
            if (total != 0) {
                throw new IllegalArgumentException(
                        "lines do not balance for " + currency.getCurrencyCode() + " (sum = " + total + ")");
            }
        });
    }
}
