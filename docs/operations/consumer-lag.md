# Kafka consumer lag

Payment-service samples committed progress for `spring.kafka.consumer.group-id` on
`payments.kafka.ledger-topic` and `payments.kafka.hold-topic`. This shows a stopped or slow
consumer even when the ledger outbox is empty. Metrics are available at `/actuator/prometheus`;
Grafana's Ledgerly overview has a consumer-progress row.

## Read the signals

| Prometheus metric | Labels | Meaning |
|---|---|---|
| `payments_kafka_consumer_lag` | group, topic | Sum of retained log offsets remaining after committed positions |
| `payments_kafka_consumer_lag_max` | group, topic | Largest partition lag |
| `payments_kafka_consumer_uncommitted_partitions` | group, topic | Partitions without a committed position; earliest retained offset is used |
| `payments_kafka_consumer_invalid_offsets` | group, topic | Positions outside retained bounds, or inconsistent broker bounds |
| `payments_kafka_lag_sample_success` | group | 1 for a fresh completed sample; 0 before first success, after failure, or when stale |
| `payments_kafka_lag_sample_age_seconds` | group | Seconds since last completed good sample, or startup before the first success |
| `payments_kafka_lag_sample_failures_total` | group | Failed sampling attempts |

Kafka offsets are positions, so these numbers approximate backlog rather than count business
events exactly. A fresh sample can contain invalid offsets: its success is 1, its invalid count
is positive, and lag is NaN. On sampling failure or staleness, all topic gauges are NaN. Treat
unknown as unknown; do not substitute zero in queries or dashboards.

Each replica samples the same group. Use `max by (group, topic) (payments_kafka_consumer_lag)`
for a group view. Summing replicas multiplies the apparent backlog. Check per-instance sampler
health as well: a healthy replica does not establish that the others can query Kafka.

## Configure

| Property | Default | Purpose |
|---|---|---|
| `payments.kafka.enabled` | true | Existing Kafka processing switch; false also disables lag monitoring |
| `payments.kafka.lag.enabled` | true | Enable the additional Admin client and lag metrics |
| `payments.kafka.lag.poll-interval-ms` | 15000 | Delay between completed sampling attempts; also initial delay |
| `payments.kafka.lag.timeout` | 5s | Total Admin sampling deadline and bounded client close timeout |
| `payments.kafka.lag.max-staleness` | 60s | Age at which a cached sample becomes unavailable |

The poll interval must be positive, timeout must be between 1ms and 2147483647ms, and maximum
staleness must exceed the poll interval. Allow room for both the interval and timeout when tuning
staleness. If changing maximum staleness, review the alert's additional 60-second age condition
and the dashboard thresholds. Monitoring requires `spring.kafka.consumer.auto-offset-reset=earliest`
and rejects other reset policies at startup. Disable `payments.kafka.lag.enabled` if using another
policy. Missing commits represent retained backlog from earliest.

Bootstrap and security settings come from Spring's KafkaAdmin configuration. On secured clusters,
the monitor needs permission to describe the configured topics and consumer group and query their
offset metadata. It does not create topics, consume records, reset offsets, or join the group.
Topic creation remains part of the existing application/bootstrap setup.

## Investigate alerts

- **PaymentConsumerLagHigh:** more than 100 offsets for two minutes. Check listener errors,
  dead-letter activity, assignment/rebalance churn, and processing/database latency. Compare the
  largest partition with the total to identify skew. Check both outboxes to locate which stage
  is delayed. If a listener was intentionally stopped, resume it and verify commits advance.
- **PaymentConsumerLagMonitoringUnavailable:** failed/startup/stale sampling for one minute.
  Check the instance's `Kafka consumer lag sample failed` logs, broker connectivity, configured
  topic/group names, and Kafka access permissions. Lag is unknown until a new sample succeeds.
- **PaymentConsumerOffsetsInvalid:** invalid retained positions for one minute. Investigate
  retention, topic recreation, and recent offset resets. Compare broker earliest/latest and
  committed offsets. Determine how business events will be recovered before changing offsets;
  monitoring itself never resets them.

The existing `LedgerlyServiceDown` alert covers an application that cannot be scraped. If lag
monitoring is disabled intentionally, its series are absent and these three alerts do not fire.
An empty topic or a group with no commits is observable after a successful sample, including
when Spring Kafka listener auto-startup is disabled.

## Verify locally

```bash
mvn verify
docker run --rm --entrypoint promtool \
  -v "$PWD/monitoring/prometheus:/etc/prometheus:ro" -w /etc/prometheus \
  prom/prometheus:v3.5.0 test rules alerts.test.yml
python3 scripts/smoke-consumer-lag.py
```

The smoke needs Java 21, Python 3, Docker, and the packaged payment-service jar produced by
`mvn verify` (or `mvn -pl payment-service -am package -DskipTests`). It creates uniquely named
PostgreSQL 16/Kafka 3.9.1 containers, topics, and a consumer group. A local packaged application
starts with its business listeners and background jobs disabled. The script produces six records
per topic, verifies lag, consumes and commits, pauses only its disposable broker to verify unknown
lag and responsive scrapes, then resumes it and verifies recovery. It removes its own containers
and anonymous volumes on exit and retains the application log under the OS temporary directory.
It does not use the Compose stack or its consumer group.

See [ADR 0021](../adr/0021-consumer-lag-monitoring.md) for the sampling and lifecycle decisions.
