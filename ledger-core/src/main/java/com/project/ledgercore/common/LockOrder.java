package com.project.ledgercore.common;

import java.util.Comparator;
import java.util.UUID;

/** The one global order in which accounts are locked (see ADR 0004). */
public final class LockOrder {

    /**
     * Ascending id, compared as the canonical lower-case text form. This is exactly how PostgreSQL orders
     * a uuid column, so Java and SQL agree. {@link UUID#compareTo} orders differently and must not be used.
     */
    public static final Comparator<UUID> BY_ID = Comparator.comparing(UUID::toString);

    private LockOrder() {
    }
}