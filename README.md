# Ledgerly

A mini payments core in Java 21 and Spring Boot 3: double-entry ledger, idempotent transfers,
holds and captures, saga-based external payments, event-driven fraud and notification services,
reconciliation and audit.

Built in small, atomic commits, every day. Design decisions are in [`docs/adr`](docs/adr).

## Run locally

```bash
docker compose up -d                       # PostgreSQL 16
mvn -pl ledger-core spring-boot:run
curl localhost:8080/actuator/health
```

## Test

```bash
mvn test       # fast unit tests
mvn verify     # + integration tests against real PostgreSQL via Testcontainers (Docker required)
```

## Roadmap

| Week | Focus |
|------|-------|
| 1 | Ledger foundation: schema invariants, accounts, Money, REST API, error model |
| 2 | Transfers: idempotency, benchmarked locking strategies, concurrency tests, balance snapshots |
| 3 | payment-service: payment state machine, holds and captures, outbox, Kafka event contracts |
| 4 | External payments: rail-simulator, saga with timeouts, retries and compensation, dead-letter queue |
| 5 | fraud-service, notification-service, reconciliation-service, gateway and rate limiting |
| 6 | Security (Keycloak, RBAC, PII encryption), audit log, multi-currency, statements read model |
| 7 | Observability, partitioning and query tuning, load tests, chaos tests |
| 8 | Kubernetes and Helm, CI/CD, hardening, documentation, demo, interview prep |