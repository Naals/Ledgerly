package com.project.ledgercore.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.idempotency.IdempotencyService.Outcome;
import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.support.ConcurrentRunner;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IdempotencyServiceIT extends AbstractIntegrationTest {

    public record Result(String value) {
    }

    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);

    @Autowired
    IdempotencyService idempotency;

    @Test
    void firstCallRunsTheOperationAndALaterCallReplaysTheStoredResult() {
        String key = newKey();
        AtomicInteger runs = new AtomicInteger();

        Outcome<Result> first = idempotency.execute("test", key, HASH, Result.class,
                () -> new Result("run-" + runs.incrementAndGet()));
        Outcome<Result> second = idempotency.execute("test", key, HASH, Result.class,
                () -> new Result("run-" + runs.incrementAndGet()));

        assertThat(first.replayed()).isFalse();
        assertThat(first.response()).isEqualTo(new Result("run-1"));
        assertThat(second.replayed()).isTrue();
        assertThat(second.response()).isEqualTo(new Result("run-1"));
        assertThat(runs).hasValue(1);
    }

    @Test
    void sameKeyWithADifferentRequestIsRejected() {
        String key = newKey();
        idempotency.execute("test", key, HASH, Result.class, () -> new Result("x"));

        assertThatThrownBy(() -> idempotency.execute("test", key, OTHER_HASH, Result.class, () -> new Result("y")))
                .isInstanceOf(IdempotencyKeyReuseException.class);
    }

    @Test
    void aFailedOperationLeavesNoKeyBehindSoARetryRunsAgain() {
        String key = newKey();
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> idempotency.execute("test", key, HASH, Result.class, () -> {
            runs.incrementAndGet();
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        Outcome<Result> retry = idempotency.execute("test", key, HASH, Result.class,
                () -> new Result("run-" + runs.incrementAndGet()));

        assertThat(retry.replayed()).isFalse();
        assertThat(retry.response()).isEqualTo(new Result("run-2"));
    }

    @Test
    void concurrentDuplicatesRunTheOperationOnlyOnce() throws Exception {
        String key = newKey();
        AtomicInteger runs = new AtomicInteger();
        List<Callable<Outcome<Result>>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> idempotency.execute("test", key, HASH, Result.class, () -> {
                int run = runs.incrementAndGet();
                sleep(150);
                return new Result("run-" + run);
            }));
        }

        List<Outcome<Result>> outcomes = ConcurrentRunner.runAll(8, calls);

        assertThat(runs).hasValue(1);
        assertThat(outcomes).filteredOn(outcome -> !outcome.replayed()).hasSize(1);
        assertThat(outcomes).extracting(Outcome::response).containsOnly(new Result("run-1"));
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
