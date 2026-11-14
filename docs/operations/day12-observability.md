# Ledgerly Observability Runbook

## Start

export GRAFANA_ADMIN_PASSWORD='your-local-password'

docker compose -f docker-compose.yml \
-f docker-compose.observability.yml up -d --build

## Interfaces

Prometheus: http://localhost:9090
Grafana:    http://localhost:3000

## Verify metrics

curl -f http://localhost:18080/actuator/prometheus
curl -f http://localhost:18081/actuator/prometheus

bash scripts/day12_smoke.sh

## Kafka failure

Symptoms:
- Outbox pending count increases
- Oldest pending-event age increases
- Outbox relay logs show publishing failures

Investigate:

docker compose logs --tail=100 kafka
docker compose logs --tail=100 ledger-core
docker compose logs --tail=100 payment-service

Check pending ledger events:

SELECT seq, event_type, attempts, last_error,
next_attempt_at
FROM outbox_events
WHERE published_at IS NULL
ORDER BY seq
LIMIT 20;

Run this SQL against the ledger database.

Do not manually delete unpublished events.

## Dead-letter events

Inspect the Kafka dead-letter topic and its headers.

Determine:
- Original topic
- Original partition and offset
- Exception type
- Exception message
- Event ID

Correct the underlying cause before replaying.

Do not replay blindly: consumers must remain idempotent.

## Payment inconsistency

When payments.events.inconsistent increases:

1. Identify the payment ID from the application logs.
2. Read the payment status.
3. Read the corresponding ledger hold and transaction.
4. Determine whether funds actually moved.
5. Escalate discrepancies for manual investigation.

Do not change financial balances directly.

## Reconciliation fallback

If payments.reconciliation.settled increases:

1. Check hold-event publication.
2. Check Kafka listener health.
3. Inspect dead-letter topics.
4. Check outbox delivery delay.
5. Investigate missing event processing.

## Local recovery test

LEDGERLY_CHAOS_ACK=yes \
bash scripts/day12_smoke.sh --chaos

This command temporarily stops Kafka.
Use only in a disposable development environment.
