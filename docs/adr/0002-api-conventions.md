# ADR 0002: API conventions: money, errors and status codes

- Status: accepted
- Date: 2026-10-17

## Decision

1. **Money is an integer plus a currency.** Requests and responses use `amountMinor` (a whole number
   of minor units, tiyn for KZT) and an ISO 4217 `currency` code. A JSON decimal would bring floating
   point rounding back through the front door.
2. **Errors are problem documents** (RFC 9457, `application/problem+json`). The `type` is
   `urn:ledgerly:problem:<code>` with a stable, machine-readable code such as `insufficient-funds`,
   so clients branch on the code, not on message text. Validation errors list every bad field.
3. **Status codes mean one thing each.** 400: the request itself is invalid. 404: a referenced
   resource does not exist. 409: valid request, but it clashes with current state (account frozen).
   422: valid request, but a business rule forbids it (insufficient funds, currency mismatch).
4. **Statements use keyset pagination.** The cursor is the id of the last entry returned.
   Unlike OFFSET it stays stable while new entries arrive and uses the `(account_id, id)` index.
5. **Every request carries a correlation id** (`X-Correlation-Id`), accepted only from a safe
   character set, echoed in the response and added to every log line.

## Known gaps

- Deposits and withdrawals are not idempotent yet: a retried request posts twice (week 2).
- The API has no authentication yet (week 6).
- A balance is a SUM over entries, which will get slow for very busy accounts (week 2: snapshots).