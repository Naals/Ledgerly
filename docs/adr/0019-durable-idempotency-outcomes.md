# ADR 0019: Durable idempotency outcomes, including business refusals

- Status: accepted
- Date: 2026-11-21
- Supersedes: failure handling in ADR 0005 for initial transfers and hold placement

## Context

Payment-service may execute one pending payment concurrently through client retries, background
jobs or multiple instances. It treats a ledger business refusal as final and keeps the same
ledger idempotency key for every execution of that payment.

Previously, ledger-core rolled back a refused operation's key. One execution could therefore
receive `insufficient-funds`, while a later execution succeeded after the account was funded.
If the first response recorded `FAILED`, the later completion update was ignored despite the
money moving. Manual authorization had the same risk of creating an untracked hold.

A payment-side lock or expiring lease does not completely solve this: after a client timeout,
the original HTTP request may still execute at the ledger, even after another attempt finishes.
The ledger must make the outcome stable at the point where money moves.

## Decision

1. Initial transfers and hold placement use `executeWithDurableRejections`. The ledger's existing
   `(scope, idempotency_key)` claim serializes all attempts for that instruction. Successes and
   expected business refusals are committed outcomes. Captures, cash operations and reversals
   retain the existing `execute` behavior: refusals roll back the key and may be retried.
2. The claim is outside a JDBC savepoint; the operation runs inside it. On a business refusal,
   the operation rolls back to that savepoint, undoing all account, posting and outbox writes.
   The key remains locked until the error status, code and message commit in the outer transaction.
   Spring's nested JDBC rollback also clears rollback-only state set by participating services.
3. The durable idempotency entry point refuses an enclosing transaction. It must commit its own
   outcome before throwing the rejection to the HTTP exception handler. No database transaction
   is added around payment-service's HTTP calls.
4. The existing 400/404/409/422 domain exception categories are retained. A repeat returns the
   same status, problem code and detail, with `Idempotent-Replayed: true`; the first refusal has
   `Idempotent-Replayed: false`. Request validation before the claim still works as before.
5. Infrastructure failures, unknown exceptions, serialization failures and commit failures
   still roll back the key and the operation. Their outcome is uncertain and may be retried.
6. Migration V18 adds separate error fields without changing stored success response bodies.
   A rejected key still rejects a different request fingerprint with `idempotency-key-reuse`.

## Compatibility and rollout

For transfers and hold placement, this deliberately changes the old "fund the account and retry
the same rejected key" behavior. After fixing a business refusal, a direct ledger client must
use a new key. A failed payment requires a new payment with a new client key. Repeating the old
key replays the refusal.

Captures deliberately remain retryable with their existing key: payment-service uses a fixed
capture key per payment and keeps an authorization active after a temporary refusal (for example,
a frozen account). Unfreezing the account must allow capture to succeed. Making capture refusals
durable would require a separate capture-attempt lifecycle, which is outside this change.

Deploy V18 and ledger-core together before relying on stable refusals. During a rolling deployment,
old ledger instances can still roll back newly rejected keys, so the guarantee starts only after
all ledger instances run the new implementation. Existing successful keys remain replayable;
historical failures were not stored and cannot be reconstructed by this migration.

## Validation

Integration coverage exercises concurrent duplicate refusals, rollback of operation writes and
participating transaction rollback-only state, all four HTTP error categories, fingerprint
conflicts, infrastructure retries, and transfer/hold retries after account funding. Capture
regressions verify that unfreezing either account permits a same-key retry without a second debit.
Success replay and concurrent success tests remain in place.
