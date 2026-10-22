# ADR 0005: Idempotency keys stored in the same transaction as the operation

- Status: accepted
- Date: 2026-10-22

## Context

Clients retry after timeouts and dropped connections. Without protection a retried transfer, deposit
or withdrawal is applied twice.

## Decision

Every money-moving POST requires an `Idempotency-Key` header (8-64 characters of `A-Za-z0-9._-`).

1. **One transaction.** The key is claimed, the operation runs and its response is stored in a single
   database transaction. The key and the operation either both exist or neither does, so there is no
   window where money moved but the key was lost, or the other way round.
2. **Claim with `INSERT ... ON CONFLICT DO NOTHING`.** When a duplicate arrives while the first request
   is still running, the insert waits for the first transaction to finish, then reads the stored
   response. Tested in SQL: 20 parallel requests with the same key, 1 executed the operation and 19
   waited and replayed its stored response.
3. **Replay.** A repeat with the same key and the same request returns the original response with
   `Idempotent-Replayed: true`. The same key with a different request is a 422
   (`idempotency-key-reuse`).
4. **Failures leave no key.** A failed operation rolls back, key included, so a retry runs again. This
   is safe because the failed attempt had no effect, and it lets a client fix the cause (for example
   deposit more money) and retry with the same key. The trade-off: only successful outcomes are
   replayed; a failure is re-evaluated.
5. **Scope.** The key is unique per (operation, key), so the same key may be used for a deposit and a
   transfer independently.
6. **Fingerprint.** The request is compared by a SHA-256 of its parsed fields, not of the raw body, so
   whitespace and field order do not matter. The algorithm is pinned by a test, because stored
   fingerprints must stay comparable across releases.

## Known gaps

- Keys are never deleted. The `created_at` index is ready for a purge job (for example, keys older
  than 24 hours).
- Keys are not tied to a client yet; they will be scoped by the authenticated client in week 6.