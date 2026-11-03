# 0014: Holds reserve funds; the database keeps them safe

## Status
Accepted (Day 8)

## Context
A card-style payment is authorized first and captured later. Between the two, the money must not be spent
elsewhere, and a capture that was authorized must not fail for lack of funds.

## Decision
- `holds` records each reservation. `accounts.held` is the sum of its ACTIVE holds, kept by trigger in the same
  transaction. `available = balance - held`.
- `CHECK (allow_negative OR balance >= held)` replaces the V7 overdraft check
  (`ck_accounts_balance_non_negative` became `ck_accounts_available_non_negative`). A new hold and any debit
  are both refused if they would spend reserved money, whatever code or SQL attempts them.
- The services also check the available balance (DebitGuard) to give clear errors; the constraint is the backstop.
- A hold's terms are immutable; it leaves ACTIVE once (CAPTURED, RELEASED or EXPIRED) and never moves again. It
  cannot be captured after `expires_at` and cannot be expired before it. Holds are never deleted.
- Capture marks the hold CAPTURED first (which frees its whole amount) and posts the debit second. The other
  order is refused by the database. A partial capture posts only the captured part; the rest is free.
- Lock order, in every flow: the hold row, then account rows in ascending id. Transfers take accounts only.
- The expiry sweep takes hold rows with SKIP LOCKED and finishes them in ascending account id. Without the sort,
  two sweeps over the same accounts deadlocked in an experiment (1 deadlock; 0 with the sort).
- Only active customer accounts can hold funds. Closing an account with a hold is refused (balance would not be 0).
- A reversal checks the available balance too: money a customer has reserved cannot be taken back from under the hold.

## Consequences
- A frozen payer cannot be captured; the hold stays ACTIVE until it expires or the account is unfrozen.
- Expiry frees funds up to one sweep interval late (30 s by default); a capture is refused the moment the hold
  runs out, sweep or not.
- Not done yet: hold events on the outbox; payment-service's authorize/capture flow (Day 9).