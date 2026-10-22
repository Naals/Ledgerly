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

Every money-moving POST needs an `Idempotency-Key` header. A retry with the same key and body is
safe: it returns the original result and moves no money.

```bash
# open two accounts and keep their ids
A=$(curl -s -X POST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' \
  -d '{"ownerRef":"aslan","currency":"KZT"}' | jq -r .id)
B=$(curl -s -X POST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' \
  -d '{"ownerRef":"dana","currency":"KZT"}' | jq -r .id)

# deposit 1000.00 KZT into A (amounts are integer minor units)
curl -s -X POST localhost:8080/api/v1/accounts/$A/deposits \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"amountMinor":100000,"currency":"KZT"}'

# transfer 300.00 KZT from A to B; run it twice with the SAME key to see a replay
KEY=$(uuidgen)
for i in 1 2; do
  curl -si -X POST localhost:8080/api/v1/transfers \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
    -d "{\"fromAccountId\":\"$A\",\"toAccountId\":\"$B\",\"amountMinor\":30000,\"currency\":\"KZT\"}" \
    | grep -E "HTTP|Idempotent-Replayed|transactionId"
done

# balances, statement, and one transaction with its balanced entries
curl -s localhost:8080/api/v1/accounts/$A/balance
curl -s "localhost:8080/api/v1/accounts/$A/entries?limit=10"
curl -s "localhost:8080/api/v1/accounts?ownerRef=aslan"

# an overdraft is a 422 problem document with a stable code
curl -s -X POST localhost:8080/api/v1/transfers \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"fromAccountId\":\"$A\",\"toAccountId\":\"$B\",\"amountMinor\":99999999,\"currency\":\"KZT\"}"
```

Design decisions: [`docs/adr`](docs/adr).
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