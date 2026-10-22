package com.project.ledgercore.account;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Locks several accounts at once without risking a deadlock.
 *
 * <p>Two transactions that need the same accounts could otherwise lock them in opposite order and wait
 * for each other forever. Here every caller takes the locks in the same global order (ascending id),
 * so a waiting cycle is impossible.
 */
@Service
public class AccountLocker {

    /**
     * Same order as PostgreSQL uses for {@code ORDER BY id} on a uuid column (the canonical lower-case
     * text form sorts exactly like the raw bytes). Do not use {@link UUID#compareTo}: it orders differently.
     */
    static final Comparator<UUID> LOCK_ORDER = Comparator.comparing(UUID::toString);

    private final AccountRepository accounts;

    public AccountLocker(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /**
     * Locks the accounts, in ascending id order, until the surrounding transaction ends.
     * Refuses to run outside a transaction, because the locks would be released immediately.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, Account> lockInOrder(Collection<UUID> ids) {
        List<UUID> ordered = ids.stream().distinct().sorted(LOCK_ORDER).toList();
        Map<UUID, Account> locked = new LinkedHashMap<>();
        for (UUID id : ordered) {
            Account account = accounts.findAndLockById(id).orElseThrow(() -> new AccountNotFoundException(id));
            locked.put(id, account);
        }
        return locked;
    }
}