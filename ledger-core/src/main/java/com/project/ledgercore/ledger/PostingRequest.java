package com.project.ledgercore.ledger;

import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A request to record one ledger transaction.
 *
 * <p>The request is validated on construction, so an invalid posting can never reach the database:
 * at least two lines, one line per account, no zero amounts, and the lines must sum to zero for every
 * currency. A REVERSAL names the transaction it reverses; no other posting may. A posting may carry a
 * {@code reference}: a label chosen by the caller (the id of a payment, say) that travels with the
 * transaction and into its event. (The database enforces the same rules as a second line of defence.)
 */
public record PostingRequest(
        TransactionType type, String description, List<PostingLine> lines,
        UUID reversesTransactionId, String reference) {

    static final int MAX_DESCRIPTION_LENGTH = 255;
    static final int MAX_REFERENCE_LENGTH = 64;

    /** A posting that does not reverse anything and has no reference. */
    public PostingRequest(TransactionType type, String description, List<PostingLine> lines) {
        this(type, description, lines, null, null);
    }

    /** A posting that may reverse something, and has no reference. */
    public PostingRequest(TransactionType type, String description, List<PostingLine> lines, UUID reversesTransactionId) {
        this(type, description, lines, reversesTransactionId, null);
    }

    public PostingRequest {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        lines = List.copyOf(lines);

        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        if (reference != null && (reference.isBlank() || reference.length() > MAX_REFERENCE_LENGTH)) {
            throw new IllegalArgumentException(
                    "reference must be between 1 and " + MAX_REFERENCE_LENGTH + " characters and not blank");
        }
        if (lines.size() < 2) {
            throw new IllegalArgumentException("a ledger transaction needs at least two lines");
        }
        if (lines.stream().map(PostingLine::accountId).distinct().count() != lines.size()) {
            throw new IllegalArgumentException("each account may appear in at most one line");
        }
        if ((type == TransactionType.REVERSAL) == (reversesTransactionId == null)) {
            throw new IllegalArgumentException(
                    "a REVERSAL must name the transaction it reverses, and no other posting may");
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