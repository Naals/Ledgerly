package com.project.ledgercore.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.ledgercore.account.Account;
import com.project.ledgercore.account.AccountRepository;
import com.project.ledgercore.account.NewAccount;
import com.project.ledgercore.cash.CashService;
import com.project.ledgercore.common.Money;
import com.project.ledgercore.support.AbstractIntegrationTest;
import com.project.ledgercore.support.ConcurrentRunner;
import com.project.ledgercore.support.RecordingEventPublisher;
import com.project.ledgercore.transfer.TransferService;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxPublisherIT extends AbstractIntegrationTest {

    private static final Currency KZT = Currency.getInstance("KZT");

    @Autowired
    OutboxPublisher publisher;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    RecordingEventPublisher recorder;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    AccountRepository accounts;

    @Autowired
    CashService cash;

    @Autowired
    TransferService transfers;

    @Autowired
    ObjectMapper json;

    @BeforeEach
    void startFromAnEmptyOutbox() {
        jdbc.update("UPDATE outbox_events SET published_at = clock_timestamp() WHERE published_at IS NULL");
        recorder.reset();
    }

    @Test
    void pendingEventsAreDeliveredInOrderAndMarkedPublished() {
        OutboxEvent first = newEvent();
        OutboxEvent second = newEvent();
        OutboxEvent third = newEvent();

        int delivered = publisher.publishBatch(10);

        assertThat(delivered).isEqualTo(3);
        assertThat(recorder.published()).extracting(OutboxEvent::id)
                .containsExactly(first.id(), second.id(), third.id());
        assertThat(outbox.countPending()).isZero();
    }

    @Test
    void anEventIsDeliveredOnlyOnce() {
        newEvent();
        publisher.publishBatch(10);

        assertThat(publisher.publishBatch(10)).isZero();
        assertThat(recorder.published()).hasSize(1);
    }

    @Test
    void aFailedEventIsRetriedLaterAndDoesNotBlockTheOthers() {
        OutboxEvent first = newEvent();
        OutboxEvent broken = newEvent();
        OutboxEvent third = newEvent();
        recorder.failWhen(event -> event.id().equals(broken.id()));

        assertThat(publisher.publishBatch(10)).isEqualTo(2);
        assertThat(recorder.published()).extracting(OutboxEvent::id).containsExactly(first.id(), third.id());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT attempts, last_error, published_at FROM outbox_events WHERE seq = ?", broken.seq());
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat((String) row.get("last_error")).contains("simulated broker outage");
        assertThat(row.get("published_at")).isNull();

        assertThat(publisher.publishBatch(10)).isZero();   // still waiting out its delay

        jdbc.update("UPDATE outbox_events SET next_attempt_at = clock_timestamp() WHERE seq = ?", broken.seq());
        recorder.failWhen(event -> false);
        assertThat(publisher.publishBatch(10)).isEqualTo(1);
        assertThat(recorder.published()).extracting(OutboxEvent::id)
                .containsExactly(first.id(), third.id(), broken.id());
    }

    @Test
    void concurrentRelaysNeverDeliverTheSameEventTwice() throws Exception {
        for (int i = 0; i < 60; i++) {
            newEvent();
        }

        List<Callable<Integer>> relays = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            relays.add(() -> {
                int total = 0;
                int delivered;
                do {
                    delivered = publisher.publishBatch(7);
                    total += delivered;
                } while (delivered > 0);
                return total;
            });
        }

        int total = ConcurrentRunner.runAll(2, relays).stream().mapToInt(Integer::intValue).sum();

        assertThat(total).isEqualTo(60);
        assertThat(recorder.published()).extracting(OutboxEvent::id).doesNotHaveDuplicates().hasSize(60);
    }

    @Test
    void aPostingsEventReachesThePublisher() throws Exception {
        Account alice = accounts.create(NewAccount.customer("alice-" + UUID.randomUUID(), KZT));
        Account bob = accounts.create(NewAccount.customer("bob-" + UUID.randomUUID(), KZT));
        cash.deposit(alice.id(), Money.of(1_000, "KZT"));
        UUID transfer = transfers.transfer(alice.id(), bob.id(), Money.of(400, "KZT"));

        while (publisher.publishBatch(100) > 0) {

        }

        OutboxEvent event = recorder.published().stream()
                .filter(published -> published.aggregateId().equals(transfer))
                .findFirst().orElseThrow();
        assertThat(event.eventType()).isEqualTo("TransactionPosted");
        assertThat(json.readTree(event.payload()).get("type").asText()).isEqualTo("TRANSFER");
    }

    @Test
    void eventsLockedByAnotherRelayAreSkippedNotWaitedFor() throws Exception {
        newEvent();
        newEvent();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                outbox.lockPending(10);   // another relay has these events in hand
                locked.countDown();
                awaitQuietly(release);
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

            assertThat(publisher.publishBatch(10)).isZero();   // returns at once: nothing is free to take

            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(publisher.publishBatch(10)).isEqualTo(2);
    }

    private OutboxEvent newEvent() {
        UUID aggregateId = UUID.randomUUID();
        outbox.add("test", aggregateId, "TestEvent", "{\"n\": 1}");
        return outbox.findByAggregate(aggregateId).getFirst();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}