# ADR 0010: Transactional outbox for domain events

- Status: accepted
- Date: 2026-10-25

## Context

Other services (fraud, notifications, reconciliation) must hear about every committed posting. Publishing to a
broker after the commit can lose an event: the commit succeeds and the process dies before the publish.
Publishing before the commit can announce something that then rolls back.

## Decision

1. **Write the event in the same database transaction as the change** (`outbox_events`). An event exists if and
   only if the change committed. The writer refuses to run outside a transaction.
2. **A relay delivers events** through an `EventPublisher` interface. For now it logs; a Kafka implementation
   replaces that one class.
3. **At-least-once delivery.** The relay locks due events with `FOR UPDATE SKIP LOCKED`, publishes, and marks them
   in the same transaction. Tested on PostgreSQL 16: two relays locking batches got disjoint sets (1 to 10 and
   11 to 20, no overlap). If the process dies after the broker accepted an event but before the mark commits,
   the event is delivered again, so **consumers must deduplicate on the event `id`**.
4. **A failing event retries later** with a growing delay (1 s, 2 s, 4 s ... capped at 5 minutes) and does not
   block the others. The price: events are oldest-first but not globally ordered, so consumers must not
   depend on cross-event order.
5. **The event is a contract:** `TransactionPosted`, with a `version` and fixed field names, pinned by a unit
   test. Fields may be added; renaming or removing one means a new version.
6. **Lag is a metric:** `ledger.outbox.pending` and `ledger.outbox.oldest.pending.age`.

## Cost

Measured on PostgreSQL 16 (SQL level, 8 clients): transfers ran at 862 and 1,240 per second without the outbox
insert, and 1,182 and 1,127 with it. The difference is smaller than the noise of the test machine. Storage: 27,542
events averaged 372 bytes of payload and took 17 MB including indexes.

## Known gaps

- Published events are never deleted: a purge job (retention) is needed before this runs for long.
- The relay publishes while holding row locks on its batch, which is fine for a fast broker, but the publisher
  needs a short timeout.
- The relay is a scheduled poll, with up to one poll interval of added latency.