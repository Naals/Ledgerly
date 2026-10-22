package com.project.ledgercore.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.support.ConcurrentRunner;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class IdempotencyRepositoryIT extends AbstractIntegrationTest {

    private static final String HASH = "a".repeat(64);

    @Autowired
    IdempotencyRepository keys;

    @Autowired
    TransactionTemplate tx;

    @Test
    void aKeyCanOnlyBeClaimedOnce() {
        String key = newKey();

        assertThat(keys.claim("transfer", key, HASH)).isTrue();
        assertThat(keys.claim("transfer", key, HASH)).isFalse();
    }

    @Test
    void theCompletedResponseCanBeFound() {
        String key = newKey();
        keys.claim("transfer", key, HASH);
        keys.complete("transfer", key, "{\"ok\":true}");

        assertThat(keys.find("transfer", key))
                .contains(new IdempotencyRepository.StoredRequest(HASH, "{\"ok\":true}"));
        assertThat(keys.find("transfer", newKey())).isEmpty();
    }

    @Test
    void theSameKeyInAnotherScopeIsIndependent() {
        String key = newKey();

        assertThat(keys.claim("transfer", key, HASH)).isTrue();
        assertThat(keys.claim("deposit", key, HASH)).isTrue();
    }

    @Test
    void concurrentClaimsInsideTransactionsHaveExactlyOneWinner() throws Exception {
        String key = newKey();
        List<Callable<Boolean>> claims = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            claims.add(() -> tx.execute(status -> {
                boolean first = keys.claim("transfer", key, HASH);
                sleep(100);   // keep the transaction open, so the others really have to wait for it
                return first;
            }));
        }

        long winners = ConcurrentRunner.runAll(8, claims).stream().filter(Boolean::booleanValue).count();

        assertThat(winners).isEqualTo(1);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
