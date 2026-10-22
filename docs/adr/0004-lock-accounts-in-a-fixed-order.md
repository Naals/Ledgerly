# ADR 0004: Lock multiple accounts in ascending id order

- Status: accepted
- Date: 2026-10-22

## Context

A transfer debits one account and credits another, and both rows must be locked (ADR 0003).
If a transfer A to B locks A then B, while a concurrent transfer B to A locks B then A, each holds
one lock and waits for the other: a deadlock. PostgreSQL resolves it by killing one transaction.

Reproduced on PostgreSQL 16: two sessions locking the same two accounts in opposite order, one was
aborted with "deadlock detected"; both locking in ascending id order, no deadlock. In a simulation of
300 random concurrent transfers between 10 accounts with locks taken in request order, one transfer
died with a deadlock error. With ordered locking, 1,000 concurrent transfers produced none, total
money was unchanged and no balance went negative.

## Decision

All multi-account operations take their locks through `AccountLocker.lockInOrder`, which sorts the ids
and locks them one at a time in ascending order. A global order makes a waiting cycle impossible.

- The order is the lexicographic order of the canonical text form of the uuid. This equals
  PostgreSQL's `ORDER BY id` on a uuid column (checked on 5,000 random uuids), so SQL paths and Java
  paths agree. `java.util.UUID.compareTo` orders differently and must not be used.
- `Propagation.MANDATORY`: the locker refuses to run outside a transaction, where its locks would be
  released immediately.
- Idempotency keys (ADR 0005) are claimed before any account is locked, so a waiting duplicate holds
  no account lock and cannot take part in a cycle.

## Consequences

- Every future operation that locks more than one account must use `AccountLocker`.
- Ordering prevents deadlocks only among operations that follow it. One that locks in a different
  order can still deadlock with them.
- Locks are taken one statement at a time, which costs one round trip per account. Acceptable for
  two accounts; revisit for large batches.