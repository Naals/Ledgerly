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
## API tour

```bash
# open an account and keep its id
ID=$(curl -s -X POST localhost:8080/api/v1/accounts \
  -H 'Content-Type: application/json' \
  -d '{"ownerRef":"aslan","currency":"KZT"}' | jq -r .id)

# deposit 1000.00 KZT (amounts are integer minor units)
curl -s -X POST localhost:8080/api/v1/accounts/$ID/deposits \
  -H 'Content-Type: application/json' -d '{"amountMinor":100000,"currency":"KZT"}'

# withdraw 300.00 KZT, then check the balance and the statement
curl -s -X POST localhost:8080/api/v1/accounts/$ID/withdrawals \
  -H 'Content-Type: application/json' -d '{"amountMinor":30000,"currency":"KZT"}'
curl -s localhost:8080/api/v1/accounts/$ID/balance
curl -s "localhost:8080/api/v1/accounts/$ID/entries?limit=10"

# an overdraft is a 422 problem document with a stable code
curl -s -X POST localhost:8080/api/v1/accounts/$ID/withdrawals \
  -H 'Content-Type: application/json' -d '{"amountMinor":99999999,"currency":"KZT"}'
```

Error conventions and design decisions: [`docs/adr`](docs/adr).

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