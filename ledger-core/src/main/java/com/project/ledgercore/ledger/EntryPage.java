package com.project.ledgercore.ledger;

import java.util.List;

/** A page of entries plus the cursor for the next page ({@code null} on the last page). */
public record EntryPage(List<EntryView> items, Long nextBefore) {
}