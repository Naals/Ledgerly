package com.project.ledgercore.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.project.ledgercore.idempotency.IdempotencyService.Outcome;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.common.BadRequestException;
import com.project.ledgercore.common.BusinessRuleException;
import com.project.ledgercore.common.ConflictException;
import com.project.ledgercore.common.LedgerException;
import com.project.ledgercore.common.NotFoundException;
import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.support.ConcurrentRunner;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

class IdempotencyServiceIT extends AbstractIntegrationTest {

    public record Result(String value) {
    }

    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);

    @Autowired
    IdempotencyService idempotency;

    @Autowired
    AccountRepository accounts;

    @Autowired
    IdempotencyRepository keys;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void firstCallRunsTheOperationAndALaterCallReplaysTheStoredResult() {
        String key = newKey();
        AtomicInteger runs = new AtomicInteger();

        Outcome<Result> first = idempotency.executeWithDurableRejections("test", key, HASH, Result.class,
                () -> new Result("run-" + runs.incrementAndGet()));
        Outcome<Result> second = idempotency.executeWithDurableRejections("test", key, HASH, Result.class,
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
        idempotency.executeWithDurableRejections("test", key, HASH, Result.class, () -> new Result("x"));

        assertThatThrownBy(() -> idempotency.executeWithDurableRejections("test", key, OTHER_HASH, Result.class, () -> new Result("y")))
                .isInstanceOf(IdempotencyKeyReuseException.class);
    }

    @Test
    void anInfrastructureFailureLeavesNoKeyBehindSoARetryRunsAgain() {
        String key = newKey();
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> idempotency.executeWithDurableRejections("test", key, HASH, Result.class, () -> {
            runs.incrementAndGet();
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        Outcome<Result> retry = idempotency.executeWithDurableRejections("test", key, HASH, Result.class,
                () -> new Result("run-" + runs.incrementAndGet()));

        assertThat(retry.replayed()).isFalse();
        assertThat(retry.response()).isEqualTo(new Result("run-2"));
    }

    @Test
    void operationsWithoutDurableRejectionsCanRetryAfterABusinessRefusal() {
        String key = newKey();
        assertThatThrownBy(() -> idempotency.execute("capture", key, HASH, Result.class, () -> {
            throw new ConflictException("account-not-active", "Account frozen");
        })).isInstanceOf(ConflictException.class);
        assertThat(keys.find("capture", key)).isEmpty();

        Outcome<Result> retry = idempotency.execute("capture", key, HASH, Result.class,
                () -> new Result("captured after unfreezing"));
        assertThat(retry.replayed()).isFalse();
        assertThat(retry.response()).isEqualTo(new Result("captured after unfreezing"));
        assertThat(idempotency.execute("capture", key, HASH, Result.class, () -> new Result("must not run")))
                .isEqualTo(new Outcome<>(retry.response(), true));
    }

    @Test
    void aBusinessRejectionRollsBackOperationWritesAndCommitsOnlyItsOutcome() {
        String key = newKey();
        AtomicReference<UUID> accountId = new AtomicReference<>();

        assertThatThrownBy(() -> idempotency.executeWithDurableRejections("test", key, HASH, Result.class,
                () -> tx.execute(status -> {
                    accountId.set(accounts.create(NewAccount.customer("rejected-" + key,
                            Currency.getInstance("KZT"))).id());
                    // A participating transaction marks the shared connection rollback-only.
                    throw new BusinessRuleException("declined", "The operation was declined");
                })))
                .isInstanceOf(IdempotentRejectionException.class)
                .hasMessage("The operation was declined");

        assertThat(accounts.findById(accountId.get())).isEmpty();
        assertThat(keys.find("test", key)).get()
                .extracting(IdempotencyRepository.StoredRequest::rejection)
                .isEqualTo(new StoredRejection(422, "declined", "The operation was declined"));

        assertThatThrownBy(() -> idempotency.executeWithDurableRejections("test", key, HASH, Result.class, () -> {
            throw new AssertionError("A committed rejection must not run again");
        })).isInstanceOf(IdempotentRejectionException.class)
                .hasMessage("The operation was declined");
    }

    @Test
    void aTranslatedDatabaseViolationCanBeSavedAfterTheAbortedStatementRollsBack() {
        String key = newKey();
        assertThatThrownBy(() -> idempotency.executeWithDurableRejections("test", key, HASH, Result.class,
                () -> tx.execute(status -> {
                    try {
                        jdbc.update("INSERT INTO accounts (owner_ref, type, currency, allow_negative) "
                                + "VALUES (NULL, 'CUSTOMER', 'KZT', FALSE)");
                    } catch (DataIntegrityViolationException exception) {
                        throw new BusinessRuleException("invalid-account", "The database refused the account");
                    }
                    throw new AssertionError("The invalid SQL must fail");
                })))
                .isInstanceOf(IdempotentRejectionException.class)
                .hasMessage("The database refused the account");

        assertThat(keys.find("test", key)).get()
                .extracting(IdempotencyRepository.StoredRequest::rejection)
                .isEqualTo(new StoredRejection(422, "invalid-account", "The database refused the account"));
    }

    @ParameterizedTest
    @MethodSource("rejections")
    void aRejectionReplaysItsHttpStatusCodeAndMessage(LedgerException failure, int status) {
        String key = newKey();
        AtomicInteger runs = new AtomicInteger();
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean replayed = attempt > 0;
            assertThatThrownBy(() -> idempotency.executeWithDurableRejections("test", key, HASH, Result.class, () -> {
                runs.incrementAndGet();
                throw failure;
            })).isInstanceOfSatisfying(IdempotentRejectionException.class, rejection -> {
                assertThat(rejection.status()).isEqualTo(status);
                assertThat(rejection.code()).isEqualTo(failure.code());
                assertThat(rejection.getMessage()).isEqualTo(failure.getMessage());
                assertThat(rejection.replayed()).isEqualTo(replayed);
            });
        }
        assertThat(runs).hasValue(1);
    }

    static Stream<Arguments> rejections() {
        return Stream.of(
                Arguments.of(new BadRequestException("bad", "Invalid operation"), 400),
                Arguments.of(new NotFoundException("missing", "Account missing"), 404),
                Arguments.of(new ConflictException("frozen", "Account frozen"), 409),
                Arguments.of(new BusinessRuleException("insufficient-funds", "Not enough funds"), 422));
    }

    @Test
    void aRejectedKeyStillRefusesADifferentRequest() {
        String key = newKey();
        assertThatThrownBy(() -> idempotency.executeWithDurableRejections("test", key, HASH, Result.class, () -> {
            throw new BusinessRuleException("declined", "Declined");
        })).isInstanceOf(IdempotentRejectionException.class);

        assertThatThrownBy(() -> idempotency.executeWithDurableRejections("test", key, OTHER_HASH, Result.class, () -> new Result("x")))
                .isInstanceOf(IdempotencyKeyReuseException.class);
    }

    @Test
    void anEnclosingTransactionIsRefusedBeforeAnyOperationRuns() {
        String key = newKey();
        assertThatThrownBy(() -> tx.execute(status -> idempotency.executeWithDurableRejections("test", key, HASH, Result.class,
                () -> new Result("must not run"))))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(keys.find("test", key)).isEmpty();
    }

    @Test
    void concurrentDuplicatesCannotSucceedAfterTheFirstAttemptWasRejected() throws Exception {
        String key = newKey();
        AtomicInteger runs = new AtomicInteger();
        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> {
                try {
                    idempotency.executeWithDurableRejections("test", key, HASH, Result.class, () -> {
                        if (runs.incrementAndGet() == 1) {
                            sleep(150);
                            throw new BusinessRuleException("insufficient-funds", "Not enough funds");
                        }
                        // Models changed account conditions between attempts: only a new key may retry.
                        return new Result("funded now");
                    });
                    return "completed";
                } catch (IdempotentRejectionException rejection) {
                    return rejection.code();
                }
            });
        }

        assertThat(ConcurrentRunner.runAll(8, calls)).containsOnly("insufficient-funds");
        assertThat(runs).hasValue(1);
    }

    @Test
    void concurrentDuplicatesRunTheOperationOnlyOnce() throws Exception {
        String key = newKey();
        AtomicInteger runs = new AtomicInteger();
        List<Callable<Outcome<Result>>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> idempotency.executeWithDurableRejections("test", key, HASH, Result.class, () -> {
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
