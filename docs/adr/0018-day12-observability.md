# ADR 0018: Production observability and event recovery

## Status
Accepted for Day 12 implementation; runtime verification pending.

## Context

Ledgerly uses two Spring Boot applications, PostgreSQL,
transactional outboxes and Apache Kafka.

Both services already expose application metrics through
Micrometer, but the project has no central monitoring system.

Failures can therefore remain unnoticed:

- Outbox events waiting too long for delivery
- Kafka events moved to dead-letter topics
- Ledger and payment state inconsistencies
- Reconciliation handling payments missed by events
- Unavailable application instances

## Decision

### 1. Prometheus

Prometheus scrapes both services every 15 seconds using
the Spring Boot Actuator Prometheus endpoints.

Management endpoints are served on dedicated ports
inside the Docker network.

### 2. Grafana

Grafana reads Prometheus metrics and provides a provisioned
dashboard for service health, outbox lag, event failures,
reconciliation and HTTP performance.

### 3. Alert rules

Six Prometheus alerts detect availability failures,
outbox delays, dead letters, payment inconsistencies
and reconciliation fallback activity.

These rules do not yet deliver external notifications.
Alertmanager integration is a separate task.

### 4. Outbox reliability

An outbox event remains pending when Kafka cannot confirm
its delivery.

The existing relay retries failed delivery attempts.

Consumers continue to use event IDs to handle duplicate
deliveries safely.

### 5. Recovery testing

An opt-in smoke test interrupts local Kafka, performs a
ledger transfer, verifies pending outbox events and checks
that delivery resumes when Kafka returns.

## Trade-offs

- Polling Prometheus is simpler than distributed tracing,
  but does not provide a complete request execution path.
- Outbox backlog metrics identify delivery delays, but
  do not prove that consumers processed each event.
- Reconciliation counters reveal fallback activity but
  require investigation to determine the cause.
- A single local Prometheus/Grafana instance is sufficient
  for development, not high-availability production use.

## Future work

- Alertmanager notifications
- OpenTelemetry distributed tracing
- Structured correlation IDs across HTTP and Kafka
- Consumer lag monitoring
- Long-term metrics retention
- Production authentication and network isolation
