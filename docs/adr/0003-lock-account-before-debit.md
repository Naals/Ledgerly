# ADR 0003: Lock the account row before debiting it

- Status: accepted (to be benchmarked against alternatives in week 2)
- Date: 2026-10-17

## Context

A withdrawal reads the balance, checks it, then writes. Two concurrent withdrawals can both read the
same balance and both pass the check, overdrawing the account.

This was reproduced against PostgreSQL 16 in SQL: 20 parallel sessions each withdrawing 100 from a
balance of 1,000, with a 50 ms pause between the check and the write to widen the window.
Without a lock all 20 succeeded and the balance ended at -1,000.

## Decision

Lock the account row (`SELECT ... FOR NO KEY UPDATE`) at the start of every operation that debits a
customer account, before reading its balance. Concurrent debits then queue up, and each one sees the
balance left by the previous one. The same experiment with the lock: exactly 10 succeeded, balance 0.

- `FOR NO KEY UPDATE`, not `FOR UPDATE`: it serialises debits equally well but does not block
  inserts that merely reference the account (the foreign key takes `FOR KEY SHARE`), so deposits are
  not held up by withdrawals. Measured: with another transaction holding the lock for 3 s, a deposit
  to the same account waited 2.4 s under `FOR UPDATE` and completed immediately under
  `FOR NO KEY UPDATE`.
- This relies on the default READ COMMITTED isolation: after the lock is granted, the balance query
  takes a fresh snapshot and sees the previous withdrawal. Under REPEATABLE READ it would not.

## Consequences

- Every future operation that debits a customer account must follow the same rule. Transfers
  (week 2) debit one account and credit another, so they must lock the debited account, and when
  they lock several accounts they must do it in a fixed order (by id) to avoid deadlocks.
- Debits on one account are serialised. Other accounts are unaffected.
- Alternatives to benchmark in week 2: optimistic locking with a version column, a conditional
  `UPDATE` on a cached balance, and SERIALIZABLE isolation with retries.