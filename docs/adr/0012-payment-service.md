# ADR 0012: payment-service

- Status: accepted
- Date: 2026-10-26

## Decision

1. **Its own database, its own process.** payment-service talks to the ledger only through its API and its events,
   never through its tables.
2. **The payment lifecycle is a state machine enforced by the database:** PENDING to COMPLETED or FAILED,
   COMPLETED to REVERSED, nothing else. A trigger refuses every other move, refuses edits to the request, and
   refuses deletes. `PaymentStatus.canMoveTo` mirrors it, and a test asks the database about every pair of
   statuses and checks the two agree.
3. **No database transaction during the HTTP call.** Register (commit), call the ledger, record the outcome. A crash
   in between leaves the payment PENDING, and a retry job finishes it.
4. **Two idempotency keys chain.** The client's key makes the payment unique; the payment id is the ledger's
   idempotency key. Repeating a request is safe at both hops. Verified on PostgreSQL 16: 16 concurrent creates with
   one key inserted exactly one row.
5. **The ledger's answer is classified.** A refusal (4xx with a problem code) is final: the payment fails with that
   code. Anything that leaves the outcome unknown (5xx, 408, 429, a timeout, an answer without a usable transaction
   id) is not final: the payment stays PENDING and is retried. Retrying is safe because of the idempotency chain.
6. **Backoff is derived from the data.** A payment is retried when its `updated_at` is older than 30 s, and every
   failed attempt touches it, so the next try waits another 30 s.
7. **Payments follow the ledger.** When the ledger reverses the transaction that paid a payment, the
   `TransactionPosted` event makes it REVERSED. The event id and the update are recorded in one transaction.

## Known gaps

- A reversal event can arrive before the payment has stored its ledger transaction id (a reversal within
  milliseconds of the posting), and would then match no payment and be dropped. Closing this needs a
  `reference` carried through the ledger transaction and the event, so a payment can be found by its own id.
- No authentication yet, and payment-service does not publish events of its own yet (its own outbox is next).
- Timeouts are fixed at 3 s; circuit breaking comes with the resilience work.