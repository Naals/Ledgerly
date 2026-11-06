# 0016: Payment events: a trigger-written outbox, delivered in order per payment

## Status
Accepted (Day 10)

## Context
Other services need to react to a payment's lifecycle (authorized, captured, cancelled, expired, failed, reversed).
Publishing to Kafka from the service after the database commit can lose events (the process dies in between) or
announce changes that never committed (the publish succeeds, the commit fails). A payment's events also have to arrive
in the order they happened in.

## Decision
- **The database writes the event.** A trigger on `payments` inserts one row into `outbox_events` for every change of
  `status`, in the same transaction (V4). An event exists if and only if the status change committed; no code path,
  including hand-written SQL, can change a status without one. Updates that do not change the status (a retry
  counter) emit nothing; neither does a rolled-back or refused transition.
- **One event type, one topic, key = payment id.** `PaymentStatusChanged` (v1) on `payments.payment-status-changed.v1`
  carries `status`, `previousStatus`, `sequence` (the payment's own status-change counter, unaffected by retention) and
  the payment's details. Separate topics per outcome would lose the ordering Kafka gives within one partition.
- **The relay hands out only a payment's oldest undelivered event.** Measured on PostgreSQL with three concurrent relays
  and 25% simulated broker failures: 23 of 40 payments had events sent out of order with the ledger's relay query (a
  failed event is postponed and later ones overtake it); 0 of 40 with the per-payment guard. The cost: an event that
  keeps failing holds back the later events of its own payment, and nobody else's. `payments.outbox.oldest.pending.age`
  shows it.
- **At-least-once.** An event is marked delivered only after the broker confirmed it. Consumers deduplicate on the
  `event-id` header and can drop a late event whose `sequence` is not newer than what they hold.
- **Retention.** Delivered events are deleted after 7 days; undelivered events can never be edited or deleted (trigger).
  Events start at V4: payments that changed state earlier have none.
- **The relay is a copy of ledger-core's.** Deliberate for now; extract a shared library when a third service needs one.
- **Dead letters are visible.** The dead-letter record's partition is chosen by the producer (the default reused the
  source partition and fails when the dead-letter topic has fewer), and a monitor counts everything that lands on a
  `.DLT` topic. The "ledger moved money for a dead payment" disagreement is a counter too.

## Consequences
- An event can be delivered twice; consumers must be idempotent.
- A permanently failing event (no topic configured, a payload the broker refuses) blocks that payment's later events
  until someone acts; the alert on the oldest pending age is how it gets noticed.
- The event's JSON is built in SQL; the contract test (`PaymentEventContractIT`) compares it with the shared samples.
- Not done yet: the ledger's own outbox has no cleanup and no per-aggregate guard (it does not need the guard).