# ADR 0009: Reversals as compensating transactions

- Status: accepted
- Date: 2026-10-25

## Context

The ledger is append-only (ADR 0001), so a mistake cannot be edited away. It has to be corrected by a new
transaction.

## Decision

A **reversal** is a new transaction of type REVERSAL that names the transaction it reverses and whose entries
are exactly the opposite of the original's.

1. **The database enforces the rules:**
    - a REVERSAL names its original, and only a REVERSAL may (CHECK);
    - a transaction is reversed at most once (UNIQUE on `reverses_transaction_id`);
    - a reversal cannot be reversed;
    - the reversal's entries are the exact opposite of the original's, checked at COMMIT by a deferred
      trigger, the same way unbalanced transactions are refused.
      Tested on PostgreSQL 16: a wrong amount, and a balanced reversal that credits the wrong accounts, were both
      refused; 8 concurrent reversals of one transaction produced exactly 1 commit and 7 unique violations.
2. **The service builds the reversal itself** from the stored entries, so it is exact by construction. It locks
   every account the transaction touched in the global order (ADR 0004), and only then checks, under those
   locks: already reversed, accounts active, and that the money to be taken back is still there.
3. **A reversal never creates a negative balance.** If the receiver already spent the money, the reversal is
   refused (`insufficient-funds`), and the situation needs a business decision, not an automatic overdraft.
4. **The API needs an `Idempotency-Key`** (ADR 0005); a retry replays the same reversal.

## Consequences

- Reversals are all-or-nothing: no partial refunds yet.
- The deferred checks fire at COMMIT, after the service call returned, so a hand-written wrong reversal surfaces
  as a database error rather than a business error. Reversals built by the service cannot trigger them.
- A reversal is a normal transaction: it appears in statements, moves the running balance, and writes its own
  event to the outbox.