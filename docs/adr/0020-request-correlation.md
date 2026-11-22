# ADR 0020: Correlation across HTTP, database outboxes, and Kafka

- Status: accepted
- Date: 2026-11-22

## Context

A payment request crosses two services and can cause additional state changes through Kafka.
The ledger's existing HTTP correlation filter stopped at that service boundary. Scheduled
retries, event relays, and database-written events could not be linked to the initiating request.

## Decision

1. Both HTTP APIs accept and echo `X-Correlation-Id`. IDs contain 1–64 ASCII letters, digits,
   dots, underscores, or hyphens. Missing or invalid IDs are replaced with a UUID. The scoped
   context restores the caller's previous logging context, including on exceptions.
2. Payment-service forwards the active ID on every ledger HTTP operation. Background payment
   execution and reconciliation use the payment UUID when no request context exists. A direct
   ledger-client hold lookup without an active context falls back to the hold UUID.
3. Correlation belongs to the operation that caused an event. Ledger migration V19 and payment
   migration V5 add nullable, validated `outbox_events.correlation_id` metadata. Existing JSON
   payloads, topic names, event IDs, and idempotency fingerprints do not change. The outbox
   protection triggers forbid editing correlation, including null-to-value changes.
4. Application-written ledger postings copy the active context into the outbox INSERT. Payment
   status updates set a transaction-local PostgreSQL setting before the UPDATE so the status
   trigger captures the same ID. Each update binds its own context; transaction completion
   clears the setting before a pooled connection is reused. HTTP calls remain outside database
   transactions.
5. Hold status events are deferred until commit to include the completed capture transaction.
   A BEFORE trigger snapshots the current correlation into `holds.status_correlation_id` when
   status changes. The deferred event uses its captured NEW row rather than the transaction's
   last setting. Two differently correlated hold changes in one transaction retain their own IDs.
6. Relays publish the persisted ID in the optional Kafka `correlation-id` header. Retrying delivery
   never substitutes a relay-thread ID. Relays also scope their logs to that ID, falling back to
   the event UUID for legacy events without metadata.
7. Payment consumers restore the Kafka correlation around the handler, including any downstream
   payment status event. Missing, oversized, or unsafe correlation metadata falls back to the
   valid `event-id`. Event identity remains mandatory for business handlers and deduplication.
   Dead-letter logging can use a deterministic topic/partition/offset identity if the event ID
   itself is malformed. Every listener restores the previous thread context afterwards.

## Semantics and compatibility

- A correlation ID is diagnostic metadata, not authentication or an idempotency key.
- Each new API request can supply a new ID. A replay echoes its current request ID, while an
  already committed event retains the ID of the request that originally produced it.
- A payment may therefore have several correlation IDs over its lifecycle. Payment, hold,
  transaction, and event IDs remain the durable identifiers connecting that lifecycle.
- An initially unsuccessful HTTP attempt and a later scheduled payment retry can use different
  IDs. The retry uses the payment UUID; this change does not persist a payment's original HTTP ID.
- Raw SQL and hold expiry without request context may produce events with no correlation metadata.
  Consumers and relay logs still have a stable event-ID fallback. No historical origin is invented.
- Deploy each migration with its service. Older consumers ignore the additional Kafka header;
  newer consumers handle older events without it. This does not require a v2 event contract.
- The small context class is duplicated between the two independent applications, like their
  existing outbox support. It introduces no shared runtime module or distributed tracing backend.

## Validation

Unit tests cover format validation, nested contexts, exception cleanup, all ledger HTTP calls,
background payment scopes, and listener fallbacks. PostgreSQL tests cover durable metadata,
immutability, rollback, connection reuse, and deferred hold events. Kafka tests inspect the
transport header. `scripts/smoke-correlation.sh` checks automatic payment HTTP propagation and
both reversal and hold-event paths through the running services.

Verified on 2026-11-22: 656 unit and integration tests passed across both modules. The smoke
test passed all three paths with the packaged Java applications, PostgreSQL 16, and Kafka 3.9.1
in an isolated local stack. This also exposed and fixed a missing default `TransactionPosted`
topic mapping that publisher tests with hand-built configuration had not covered.
