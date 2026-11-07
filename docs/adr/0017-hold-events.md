# 0017: Hold events: the ledger says what became of a hold; polling is the safety net

## Status
Accepted (Day 11). Replaces the transfer-based capture handler of ADR 0015.

## Context
payment-service learned that a hold ended only by asking (the answer to its own capture or cancel, a polling job) or by
inferring a capture from a TRANSFER event whose reference happened to be a payment id. An expiry could only be found by
polling, and the polling job looked as soon as an authorization ended, so it raced anything else that might settle it.

## Decision
- **The ledger announces it.** A trigger on `holds` writes one `HoldStatusChanged` event per status change into the
  outbox, in the same transaction (V17), delivered by the existing relay to `ledger.hold-status-changed.v1`.
  Placing a hold emits nothing.
- **The trigger is deferred.** A capture frees the hold first, posts the transfer, and records the transfer on the hold
  last. An ordinary trigger announced the capture with `ledger_transaction_id = NULL` (measured); the deferred one runs at
  commit, reads the hold as it is then, and carries the transaction.
- **One decision table.** payment-service maps (hold state, payment status) to an action. Only an AUTHORIZED payment is
  ever changed; most events are echoes of its own actions and change nothing; a PENDING payment makes the event wait and
  be redelivered; a contradiction about money (money moved for a payment we consider dead, or a payment we consider paid
  whose hold the ledger says was given back) is counted and logged, never hidden.
- **One fact, one signal.** The hold event replaces the transfer-based capture handler: it is explicit where that one
  inferred.
- **Reconciliation is the safety net.** It only looks at authorizations that ended more than five minutes ago, and every
  payment it settles is counted in `payments.reconciliation.settled`: the events should already have done it.
- **The ledger's outbox is protected and pruned** like the payment outbox (V16): undelivered events cannot be edited or
  deleted; delivered ones are deleted after seven days.

## Consequences
- Rollout order: ledger-core first (V16, V17, the topic mapping `HoldStatusChanged` in `application.yml`), then
  payment-service. An event type without a topic is retried forever by the ledger's relay.
- Holds that ended before V17 were never announced; their payments are settled by reconciliation after the grace period.
- A hold status this service does not know (a new one added later) is dead-lettered and counted, not guessed at.
- An authorized payment now becomes EXPIRED or CANCELLED within seconds of its hold ending, not within a polling interval.