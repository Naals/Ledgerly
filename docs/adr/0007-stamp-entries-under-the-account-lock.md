# ADR 0007: Stamp customer entries under the account lock

- Status: accepted
- Date: 2026-10-24

## Context

A statement needs an order of entries and a balance after each line. The entry id looks like an order, but it
is not one. An entry draws its id from the sequence before its trigger locks the account row, so when several
postings are in flight the two orders differ.

Measured on PostgreSQL 16: 16 clients depositing to and withdrawing from one account for 10 seconds (9,356
postings). 9,053 of the 9,357 entries sat at a different position ordered by id than in the order they were
applied. Checking each entry's running balance against the previous one, ordered by id: 8,884 mismatches.
Ordered by the sequence described below: none.

## Decision

The entry trigger (BEFORE INSERT) runs while the account row is locked and stamps each customer entry with:

- `account_seq`: 1, 2, 3 ... per account. It lives in the account row, so a rolled-back posting rolls its number
  back too: the numbers are gap-free.
- `balance_after`: the account's balance right after the entry.
- `posted_at`: `GREATEST(clock_timestamp(), previous posted_at)`, so it never goes backwards for an account,
  even if the server clock is stepped back.

The bank's own accounts are not stamped, for the hot-row reason in ADR 0006.

- A statement is ordered by `account_seq`, which is also its paging cursor. Page query: an index scan on
  `(account_id, account_seq)`, 0.55 ms for a 51-row page on an account with 100,000 entries.
- The balance at a past moment is one index probe on `(account_id, posted_at DESC, account_seq DESC)`:
  0.06 to 0.5 ms on the same account, and equal to a brute-force SUM.

## Consequences

- Stamping costs nothing measurable: transfers ran at 1,081 per second against 1,056 before (same data,
  within the noise of the test machine).
- Existing entries were backfilled by V9 (722,758 entries in 19.7 s). The append-only trigger is lifted for
  that one backfill only, inside the migration's transaction. Their `posted_at` is the transaction start time
  (`created_at`), which is the best information that exists for history.
- The balance trigger, the stamping and the lifecycle guard (ADR 0008) are one trigger function on one row lock.
- `LedgerInvariants.assertEntryStampsAreConsistent` checks contiguity, the running-balance chain and posting
  times after the concurrency tests.