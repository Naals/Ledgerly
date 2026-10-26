# ADR 0008: Account lifecycle, enforced by the database

- Status: accepted
- Date: 2026-10-24

## Decision

1. **Moves.** ACTIVE and FROZEN switch back and forth, either can be CLOSED, and CLOSED is final. Asking for
   the status an account already has changes nothing and returns success, so a repeated request is safe.
2. **A frozen account takes no entries at all**, credits included, like a closed one.
3. **The database guards the rules**, so no application bug can break them:
    - `CHECK (status <> 'CLOSED' OR balance = 0)`: a closed account is always empty.
    - The entry trigger locks the account row and looks at its status first; entries on a FROZEN or CLOSED
      account are refused.
4. **Closing needs a zero balance,** read under the same row lock as the status change.
5. **Every change is recorded** in an append-only `account_status_events` table, in the same transaction, with an
   optional reason. Repeated or refused requests record nothing.
6. Only customer accounts have a lifecycle.

## Why the database guard

The service checks the status first, but a check is not a lock. A deposit can read ACTIVE, the account can then be
closed and committed, and the deposit would post to a closed account. Tested on PostgreSQL 16: a closing
transaction held the account for 2 s; a deposit that started meanwhile waited 1.44 s for the account row, saw
CLOSED and was refused. The account ended CLOSED with balance 0 and no entries. The refusal is reported as the
same `account-not-active` 409 the service gives.

## Consequences

- Freezing and closing take the account row lock, so they queue behind in-flight debits.
- There is no authentication yet, so anyone can freeze an account (week 6).
- The statuses are not reopened: a closed account stays closed, as audit trails expect.