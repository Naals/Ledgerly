# ADR 0011: Kafka for event delivery, with a shared contract

- Status: accepted
- Date: 2026-10-26

## Context

The outbox (ADR 0010) writes events reliably; they must now reach other services. Other services must be able to
rely on their shape without reading ledger-core's code.

## Decision

1. **Kafka (KRaft, single node in development)** behind the relay's `EventPublisher` interface. The relay marks an
   event published only after the broker acknowledged it (`acks=all`, idempotent producer), so the delivery
   guarantee is at-least-once: a crash between the acknowledgement and the mark means the event is sent again.
2. **One topic per event type and version** (`ledger.transaction-posted.v1`), created at startup with 3
   partitions. The key is the transaction id, so everything about one transaction stays in one partition.
   Consumers get no ordering across transactions and must not depend on it.
3. **Headers carry `event-id`, `event-type` and `aggregate-type`.** Consumers deduplicate on `event-id` without
   parsing the body (the `processed_events` table, written in the same transaction as the effect).
4. **A poison message does not block a partition:** after 3 retries, 1 s apart, it moves to `<topic>.DLT`.
5. **The contract is tested from both sides.** Sample files in `/contracts` must equal what the producer really
   produces and must parse in every consumer. Consumers ignore unknown fields. Adding a field is
   compatible; renaming or removing one means a new version and a new topic.

## Consequences

- Delivery is at-least-once and unordered across transactions; every consumer must be idempotent.
- Nothing watches the dead-letter topic yet.
- A schema registry (Avro or Protobuf) was left out for now: shared samples are cheaper at this size, and the
  registry is the next step if the number of consumers grows.
- Development runs one broker. A real deployment needs 3 brokers and replication factor 3.