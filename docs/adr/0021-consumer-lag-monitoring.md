# ADR 0021: Sample committed Kafka consumer lag independently of listeners

- Status: accepted
- Date: 2026-11-24

## Context

Healthy outboxes show that events reached Kafka, but do not show that payment-service consumed
them. Listener-local metrics also disappear when listeners stop or lose their assignment. We
need to distinguish a consumer backlog from a monitoring failure without blocking HTTP scrapes
or payment retry jobs on Kafka.

## Decision

1. Payment-service owns a Kafka Admin client using the application's existing KafkaAdmin
   connection and security configuration. It samples only its configured consumer group and
   ledger transaction/hold topics. Topic metadata is refreshed each time to include new partitions.
2. Each sample reads committed group offsets, retained earliest offsets, and latest offsets.
   Each Admin request and future wait uses the remaining budget of one monotonic deadline.
   The client closes with a bounded timeout. Monitoring performs no group or topic mutations.
3. A partition's lag is latest minus committed offset. With no commit, use retained earliest
   and count the partition separately as uncommitted. This matches the application's default
   `auto-offset-reset=earliest`. Monitoring rejects other reset policies at startup to keep the
   interpretation explicit; deployments using those policies can disable lag monitoring.
   A specifically absent group is treated as having no commits. Other broker failures fail the sample.
4. A commit outside the retained range, or inconsistent broker bounds, marks that partition
   invalid. The topic's total and maximum lag become NaN; an invalid-offset count remains available.
   We never turn an invalid position into a healthy zero.
5. A dedicated single-thread executor samples with a fixed delay, separate from the existing
   Spring scheduled jobs. Scrapes read a cached immutable snapshot. Startup, failed samples,
   and stale samples expose NaN topic gauges and sample-success zero. A failed sample invalidates
   the previous value immediately. Age continues from the last good sample (construction before
   the first success), and a counter records failed sampling attempts.
6. Metrics have only configured group/topic tags; sampler health has a group tag. Prometheus
   adds instance labels. Replicas measure the same group, so backlog and invalid-offset queries
   use `max by (group, topic)`. Sampler health alerts retain instance identity.
7. Both Kafka processing and lag monitoring must be enabled. Disabling either removes the
   sampler, metrics, and additional Admin client. No database or event-contract changes are needed.

## Limits and operations

Offsets measure log-position distance, not an exact count of business events. Compaction and
transaction/control records affect that interpretation. Reads are sampled and not atomic; topic
deletion/recreation, retention, or offset resets during a sample can produce an invalid sample.
The next successful poll refreshes it. This is diagnostic monitoring, not a consistency proof.

The sampler continues when listeners stop while the application stays alive. A stopped application
is covered by the existing service-down alert. Missing metrics after intentional monitoring disablement
do not create a lag alert. Deployments that require the feature should also enforce its configuration.

Defaults are a 15-second poll delay, a 5-second sample timeout, and 60-second maximum staleness.
The alert rules detect backlog above 100 offsets for two minutes, invalid offsets for one minute,
and unavailable monitoring for one minute. Thresholds are starting points for the local stack.
See the [runbook](../operations/consumer-lag.md) for tuning and investigation.

## Validation

Unit tests cover retained bounds, absent commits, aggregation, deadlines, interruptions, failed
and stale snapshots, configured labels, and enable/disable lifecycle. A real Kafka integration
test checks backlog, commits, stopped consumers, and partition expansion. Promtool fixtures check
threshold duration, recovery, duplicate replicas, NaN, startup, stale samples, and invalid offsets.
The disposable packaged-application smoke test checks backlog, catch-up, responsive HTTP scrapes
during a broker pause, and recovery after the broker resumes.
