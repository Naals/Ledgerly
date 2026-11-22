# Following a request across Ledgerly

Both services return `X-Correlation-Id` on API responses. Supply a recognizable safe ID when
investigating an operation; otherwise the receiving service creates one.

```bash
curl -i -H 'X-Correlation-Id: support-case-42' \
  http://localhost:8081/api/v1/payments/"$PAYMENT_ID"
```

Valid IDs contain 1–64 ASCII letters, digits, dots, underscores, or hyphens. Invalid values are
replaced. The ID is logged in brackets alongside the log level. A read-only lookup has its own
request ID; it does not change the correlation on earlier payment events.

## Follow an event

Use the response ID to search application logs:

```bash
docker compose logs ledger-core payment-service | rg --fixed-strings '[support-case-42]'
```

Query `outbox_events` in the appropriate database to connect the request to its events:

```sql
SELECT seq, id, aggregate_type, aggregate_id, event_type, correlation_id,
       created_at, published_at, attempts
FROM outbox_events
WHERE correlation_id = 'support-case-42'
ORDER BY seq;
```

Ledger uses the `ledger` database; payment-service uses `payments`. These are diagnostic reads;
correlation metadata is immutable. Outbox retention still applies, so old delivered events may
already have been removed.

Kafka carries the same value in `correlation-id`. For example:

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic ledger.transaction-posted.v1 \
  --from-beginning --max-messages 3 --timeout-ms 10000 --property print.headers=true
```

Keep `event-id` for delivery deduplication and the payment/hold/transaction IDs for lifecycle
lookups. A capture or reversal requested later may have a different correlation from the initial
payment. An HTTP idempotency replay does not relabel the original event. Background payment retries
use the payment UUID when no request context exists. Legacy events without a correlation header
use their event UUID in consumer logs.

## Verify the complete path

Start the local stack, then run the smoke script:

```bash
docker compose up -d --build
bash scripts/smoke-correlation.sh
```

The script creates fresh demo accounts and payments and verifies:

1. A payment request forwards its ID to the ledger and both outboxes retain it.
2. A ledger reversal's ID survives Kafka and appears on the payment's REVERSED event.
3. A direct ledger capture's ID survives the deferred hold event and appears on the payment's
   COMPLETED event.

It requires `curl`, `jq`, `python3`, and Docker Compose. It uses the standard local databases and
ports; `LEDGER`, `PAYMENTS`, and `COMPOSE_PROJECT_NAME` can select another matching local stack.
It appends test transactions and leaves their history for inspection. Run it against a development
stack. The separate management ports from the observability overlay are not required.

Correlation provides log and event links. It does not collect distributed spans or request timings
in a tracing backend; OpenTelemetry integration remains a separate roadmap item.
