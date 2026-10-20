package com.project.ledgercore.account;

import com.project.ledgercore.ledger.EntryPage;
import java.util.List;

/** A page of statement entries. Pass {@code nextBefore} as the {@code before} parameter to get the next page. */
public record EntryPageResponse(List<EntryResponse> items, Long nextBefore) {

    static EntryPageResponse from(EntryPage page) {
        return new EntryPageResponse(
                page.items().stream().map(EntryResponse::from).toList(),
                page.nextBefore());
    }
}