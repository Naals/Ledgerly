# 0013: A reference travels with a transaction

## Status
Accepted (Day 8)

## Context
payment-service recorded a payment's ledger transaction id only after the ledger answered. If the transfer was
reversed in that gap, the reversal event reached payment-service before the payment knew its ledger id. The Day 7
handler looked the payment up by ledger id, found nothing, and dropped the event: the payment stayed COMPLETED.

## Decision
- A ledger transaction may carry an optional `reference` (up to 64 characters, not blank, not unique) chosen by the
  caller. It is stored (V14), returned by the API and published on the TransactionPosted event.
- A reversal inherits the reference of the transaction it reverses.
- payment-service sends its payment id as the reference. A reversal event is matched to its payment by that
  reference, not by a ledger id.
- If the payment is still PENDING, the handler throws. Its transaction rolls back (the event is not recorded as
  processed) and Kafka redelivers it with a back-off of about a minute (1, 2, 4, 8, 16, 30 s) before the dead-letter topic.
- An unreadable event is not retried and goes straight to the dead-letter topic.

## Consequences
- Adding `reference` to TransactionPosted v1 is backwards compatible: consumers already ignore unknown fields.
- A reference is a label. Two transactions may share one (a transfer and its reversal always do).
- The retries block one partition for up to a minute. Acceptable for a rare race; a retry topic is the next step if
  it is not.
- Rollout: ledger first, then payment-service (the other order also works; an old ledger ignores the field).