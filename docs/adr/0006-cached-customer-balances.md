# ADR 0006: Cache customer balances in the accounts table

- Status: accepted (refines ADR 0003)
- Date: 2026-10-23

## Context

A balance was the sum of an account's entries, and every debit reads it. Measured on PostgreSQL 16: a read
took 0.07 ms on an empty account and 15 ms on one with 100,000 entries, growing in step with history.
Busy accounts get slower exactly when they matter most.

## Decision

1. **A cached `accounts.balance`, maintained by a trigger on every ledger entry.** The trigger runs in the
   same transaction as the entry, so the cache cannot drift and nobody can forget to update it. The entries
   stay the source of truth: the cache always equals their sum, and a test checks that after every
   concurrency test.
2. **A `CHECK (allow_negative OR balance >= 0)` constraint.** The database itself refuses to overdraw a
   customer account. With no application lock and no balance read, 20 parallel withdrawals of 100 from 1,000
   committed 10 and refused 10, ending at exactly 0. The application lock and balance check (ADR 0003)
   stay, because they give a clean 422 before the database has to refuse; the constraint is the last line of
   defence, and its violation is mapped to the same `insufficient-funds` code.
3. **Only accounts that cannot go negative are cached (V8).** The first version (V7) cached every account.
   Measuring cash deposits showed why that is wrong: every deposit and withdrawal posts against the bank's
   single cash account, so each locked the same row, and all cash operations in a currency ran one after
   another (8 clients: 233 ops/s, against 996 ops/s without the hot row). The bank's own accounts are
   summed from their entries when their balance is requested, which is rare.
4. **Entries are inserted in ascending account id.** The trigger locks each account row as its entry is
   inserted. Two postings writing the same accounts in opposite order deadlocked (one session killed with
   `deadlock detected`); in ascending order, none did. A posting may therefore have at most one line per
   account. The order is the shared `LockOrder` (ADR 0004).

## Consequences

- Reading a balance and the overdraft check no longer depend on the account's history. Transfers ran at
  roughly 1.7 to 2 times the throughput in the SQL-level test.
- Writes to one customer account are serialised by its row lock. That is inherent in any cached balance and
  is limited to that account.
- A bank-owned account with a huge history is slow to read. If it ever matters: sharded counters, or periodic
  snapshots plus the entries since.
- Alternatives considered: keeping the SUM (cost grows with history); optimistic locking with a version
  column (retries under contention); periodic snapshots plus a delta (more moving parts than needed now).
  A running balance stored on each entry is planned, for statements and point-in-time balances.
- A reconciliation job that compares the cache with the entries belongs in week 4. Until then
  `LedgerInvariants` does it in tests.