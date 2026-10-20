# ADR 0001: Double-entry, append-only ledger with database-enforced invariants

- Status: accepted
- Date: 2026-10-17

## Context

Ledgerly moves money between accounts. The worst failures for a payments system are lost money,
duplicated money, and balances that cannot be explained afterwards.

## Decision

1. **Double-entry.** Every movement is a `ledger_transaction` with two or more `ledger_entries`
   whose amounts sum to zero per currency. Deposits and withdrawals go through SYSTEM accounts,
   so the books always balance.
2. **Signed minor units.** Amounts are `BIGINT` minor units (tiyn for KZT). Positive credits the
   account, negative debits it. Balance = `SUM(amount)`. No floating point anywhere.
   In code, amounts are a `Money` value object: currency-safe, with overflow-checked arithmetic.
3. **Invariants live in the database, not only in application code.**
    - A deferred constraint trigger rejects any transaction that does not balance, checked at COMMIT.
    - A composite foreign key `(account_id, currency)` makes it impossible to post an entry in a
      currency different from its account's currency.
    - Only SYSTEM accounts may have `allow_negative = TRUE`.
      The application validates the same rules first (`PostingRequest`, `NewAccount`) to fail fast
      with clear errors; the database is the second line of defence.
4. **Append-only.** Triggers block UPDATE and DELETE on ledger tables. Corrections are new,
   compensating transactions.
5. **Plain SQL via Spring JDBC in ledger-core** rather than JPA, so locking and isolation
   behaviour stays explicit and reviewable. Services with ordinary CRUD data may use JPA.

## Consequences

- Application bugs cannot silently corrupt the ledger; the database refuses invalid writes.
- A balance is a `SUM` over entries for now. A cached balance column, kept consistent under
  locking, is a later decision (ADR 0002) once concurrency requirements are measured.
- Tests that need cleanup cannot `DELETE` ledger rows; they create fresh accounts instead.