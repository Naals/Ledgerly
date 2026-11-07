# 0015: Two-step payments: authorize, then capture

## Status
Accepted (Day 9)

## Context
Some payments cannot be completed when they are requested (a booking, a pre-authorization). The money must be
reserved first and taken later, or given back. The ledger already offers holds (ADR 0014); payment-service has to
drive them without ever believing its own picture over the ledger's.

## Decision
- A payment has a `capture_mode`: AUTOMATIC (one step, as before) or MANUAL (two steps). MANUAL adds the states
  AUTHORIZED, CANCELLED and EXPIRED, a `ledger_hold_id` and the `authorization_expires_at` the ledger reported.
- The lifecycle lives in the database (V3 trigger), as before. A Java test runs the real trigger for every
  (mode, from, to) combination and compares it with `PaymentStatus.canMoveTo`; I also ran the comparison once for the
  66 combinations and the two agree. Hold, mode and request never change once set.
- The payment id is the idempotency key of the hold and of the capture (separate scopes on the ledger) and the
  hold's reference, so the capture's transfer carries it into its event (ADR 0013). Release is naturally idempotent.
- Capture and cancel are synchronous and repeatable. If the ledger cannot answer, the payment stays AUTHORIZED and the
  caller gets 503; the same call can be made again. No database transaction is held during a ledger call.
- The ledger is the source of truth about a hold. When the ledger refuses a capture or release with `hold-not-active`,
  the service asks (`settle`) and records what happened: captured (COMPLETED), released (CANCELLED) or expired (EXPIRED).
  `hold-expired` expires the payment directly.
- Catching up when an answer was lost: the ledger's hold events settle the payment (ADR 0017, which replaced the
  transfer-based capture handler first used here); a reconciliation job is the safety net behind them.
- A reversal or capture event for a payment that has not caught up (PENDING, or AUTHORIZED for a reversal) fails so
  Kafka redelivers it with a back-off of about a minute, as for reversals (ADR 0013).
- A capture event for a payment that FAILED, was CANCELLED or EXPIRED is a disagreement between the services:
  it is logged as an error, not hidden.

## Consequences
- An authorization can show AUTHORIZED for up to one reconciliation interval (60 s) plus one ledger sweep interval (30 s)
  after it expired. A capture is refused by the ledger the moment the hold runs out regardless.
- Only full captures are exposed here; the ledger supports partial ones.
- The disagreement case needs alerting; today it is an error log line.
- Not done yet: payment-service's own outbox (Day 10).