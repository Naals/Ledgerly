# What to alert on

| Metric | Condition | Meaning | First thing to check |
|---|---|---|---|
| `payments.outbox.oldest.pending.age` | > 60 s for 2 min | events are not leaving the outbox | broker reachable? `last_error` of the oldest undelivered event; a payment whose first event cannot be delivered also holds back its later ones |
| `payments.outbox.pending` | rising for 10 min | the relay is slower than the events | relay running? batch size and poll interval |
| `payments.events.dead.lettered` | any increase | an event from another service could not be handled after retries | the `.DLT` record's headers say why; fix, then replay |
| `payments.events.inconsistent` | any increase | the ledger moved money for a payment that this service considers dead | compare the payment and the ledger transaction named in the log line; this needs a person |
| `ledger.outbox.oldest.pending.age` | > 60 s for 2 min | the ledger's events are not leaving | same as above, in ledger-core |
| `payments.reconciliation.settled` | any increase | the safety net caught an authorization the hold events missed | the hold event path: is `ledger.outbox.oldest.pending.age` high, is the hold listener running, is the `.DLT` of `ledger.hold-status-changed.v1` growing |
| `payments.events.inconsistent` | any increase | now also covers a hold that contradicts its payment (captured for a dead payment, released or expired for a paid one) | compare the payment and the hold named in the log line; this needs a person |
| `payments.kafka.consumer.lag` | max across replicas > 100 for 2 min | committed consumer progress is behind Kafka | listener errors, assignment, processing latency; compare maximum partition lag |
| `payments.kafka.lag.sample.success` / `payments.kafka.lag.sample.age` | success = 0 or age > 60 s for 1 min | lag is unavailable on this instance | sampler logs, Kafka reachability, topic/group configuration and permissions |
| `payments.kafka.consumer.invalid.offsets` | max across replicas > 0 for 1 min | retained bounds and committed positions disagree | retention, topic recreation, recent offset resets |

Prometheus names replace dots with underscores and add `_total` to counters, for example
`payments_events_dead_lettered_total`. Age gauges export a `_seconds` suffix.

Consumer lag measures offset distance, not an exact event count. Failed or stale samples are
NaN, not zero; a fresh sample with invalid offsets also has unknown lag. Keep per-instance
monitoring health visible and use `max by (group, topic)` for group backlog across replicas.
See the [consumer lag runbook](consumer-lag.md) for investigation and validation commands.
