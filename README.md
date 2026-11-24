# Ledgerly

A mini payments core in Java 21 and Spring Boot 3: a double-entry ledger, idempotent transfers,
holds and captures, payment orchestration, Kafka events, and authorization reconciliation.

The implemented services are `ledger-core` and `payment-service`. External-payment sagas,
fraud and notification services, and authentication/authorization are roadmap work.
The current stack is for local development; its APIs do not enforce account ownership.

Built in small, atomic commits, every day. Design decisions are in [`docs/adr`](docs/adr).

## Run locally

```bash
docker compose up -d postgres              # ledger database only
mvn -pl ledger-core spring-boot:run
curl localhost:8080/actuator/health
```

This starts the ledger locally with the logging event publisher. Use **Run everything** below
to run both services and Kafka in containers instead.

## Test

```bash
mvn test       # fast unit tests
mvn verify     # + integration tests against real PostgreSQL and Kafka (Docker required)
```
## API tour

Every money-moving POST needs an `Idempotency-Key` header. A retry with the same key and body is
safe: it returns the original result and moves no money.
For transfers and hold creation, the ledger also remembers business rejections: after correcting
a rejected request's conditions (for example, funding an account), use a new key to try again.
Infrastructure failures remain retryable with the original key. Failed captures, cash operations,
and reversals can still be retried with the same key after their conditions change.

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
  
  # statement with entry numbers and the running balance after each entry
curl -s "localhost:8080/api/v1/accounts/$A/entries?limit=5"

# what the balance was at a moment in time (use Z for UTC)
curl -s "localhost:8080/api/v1/accounts/$A/balance?at=2026-10-24T10:00:00Z"

# freeze with a reason, look at the history, unfreeze
curl -s -X POST localhost:8080/api/v1/accounts/$A/freeze \
  -H 'Content-Type: application/json' -d '{"reason":"fraud check"}'
curl -s localhost:8080/api/v1/accounts/$A/status-history
curl -s -X POST localhost:8080/api/v1/accounts/$A/unfreeze

# an account can only be closed when it is empty
curl -s -X POST localhost:8080/api/v1/accounts/$B/close
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


```bash
# reverse a transfer: it needs an Idempotency-Key, can happen once, and only if the money is still there
TX=$(curl -s -X POST localhost:8080/api/v1/transfers \
-H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
-d "{\"fromAccountId\":\"$A\",\"toAccountId\":\"$B\",\"amountMinor\":1000,\"currency\":\"KZT\"}" | jq -r .transactionId)
curl -s -X POST localhost:8080/api/v1/transactions/$TX/reversals \
-H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
-d '{"reason":"sent to the wrong account"}'
curl -s localhost:8080/api/v1/transactions/$TX      # shows reversedByTransactionId

# every posting also wrote an event to the outbox; the relay logs it, and the lag is a metric
curl -s localhost:8080/actuator/metrics/ledger.outbox.pending
```


## Run everything

```bash
docker compose up -d --build     # PostgreSQL x2, Kafka, ledger-core (8080), payment-service (8081)
```

A payment made through payment-service, and what happens when the ledger reverses it:

```bash
# two ledger accounts, the first one funded
A=$(curl -s -X POST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' \
  -d '{"ownerRef":"aslan","currency":"KZT"}' | jq -r .id)
B=$(curl -s -X POST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' \
  -d '{"ownerRef":"dana","currency":"KZT"}' | jq -r .id)
curl -s -X POST localhost:8080/api/v1/accounts/$A/deposits \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"amountMinor":500000,"currency":"KZT"}' > /dev/null

# a payment: payment-service calls the ledger, using the payment id as the idempotency key
PAY=$(curl -s -X POST localhost:8081/api/v1/payments \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"fromAccountId\":\"$A\",\"toAccountId\":\"$B\",\"amountMinor\":100000,\"currency\":\"KZT\"}")
echo "$PAY" | jq '{id, status, ledgerTransactionId}'

# reverse it in the ledger; a moment later the payment follows through Kafka
TX=$(echo "$PAY" | jq -r .ledgerTransactionId)
curl -s -X POST localhost:8080/api/v1/transactions/$TX/reversals \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"reason":"sent to the wrong account"}' > /dev/null
sleep 2
curl -s localhost:8081/api/v1/payments/$(echo "$PAY" | jq -r .id) | jq .status     # "REVERSED"

# the events on the topic, with their headers
docker exec ledgerly-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic ledger.transaction-posted.v1 \
  --from-beginning --max-messages 3 --property print.headers=true
```

Event contracts: [`contracts`](contracts). Design decisions: [`docs/adr`](docs/adr).

- `POST /api/v1/accounts/{id}/holds`   reserve funds for a payment to another account (Idempotency-Key)
- `GET  /api/v1/holds/{id}`            read a hold
- `POST /api/v1/holds/{id}/capture`    move the held money (all, or `{"amountMinor": n}`) (Idempotency-Key)
- `POST /api/v1/holds/{id}/release`    give the funds back
- `GET  /api/v1/accounts/{id}/balance` now also returns `heldMinor` and `availableMinor`
- `POST /api/v1/transfers`             accepts an optional `reference`, echoed on transactions and events
- `POST /api/v1/payments`                  with `"captureMode": "MANUAL"`: reserve the money first (Idempotency-Key)
- `POST /api/v1/payments/{id}/capture`     take the reserved money (repeating it is harmless)
- `POST /api/v1/payments/{id}/cancel`      give it back (repeating it is harmless)


## Events
- `ledger.transaction-posted.v1`            from ledger-core, one per posted transaction (key: transaction id)
- `ledger.hold-status-changed.v1`            from ledger-core, one per hold status change (key: hold id);
  the capture's event names the transfer that moved the money
- `payments.payment-status-changed.v1`      from payment-service, one per payment status change (key: payment id,
  in order per payment; `sequence` and the `event-id` header for deduplication)
  Samples of every event are in `contracts/` and are checked on both sides.

## Request correlation

Both APIs accept and echo `X-Correlation-Id` (1–64 letters, digits, dots, underscores, or hyphens).
Payment-service forwards it to the ledger. Outbox events retain it across delivery retries and
publish it as the optional Kafka `correlation-id` header; consumers carry it into their own events.
Missing or invalid request IDs are replaced with a UUID.

Run `bash scripts/smoke-correlation.sh` against the local stack to verify the HTTP and Kafka paths.
See the [correlation runbook](docs/operations/request-correlation.md) for log/event lookups and
[ADR 0020](docs/adr/0020-request-correlation.md) for replay and background-job behavior.

## Consumer lag monitoring

Payment-service exports committed Kafka lag for its ledger and hold topics, including when
listeners stop. Sampling failures and stale data appear as unknown lag with separate health
metrics. Prometheus alerts and Grafana panels cover backlog, invalid offsets, and unavailable
monitoring. See the [consumer lag runbook](docs/operations/consumer-lag.md) for configuration
and `python3 scripts/smoke-consumer-lag.py` for an isolated backlog/outage/recovery check.
