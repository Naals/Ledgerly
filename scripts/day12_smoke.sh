#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

COMPOSE=(
  docker compose
  -f docker-compose.yml
  -f docker-compose.observability.yml
)

LEDGER="http://127.0.0.1:8080"
LEDGER_METRICS="http://127.0.0.1:18080/actuator/prometheus"
PAYMENT_METRICS="http://127.0.0.1:18081/actuator/prometheus"
PROMETHEUS="http://127.0.0.1:9090"

for command in curl jq python3 docker; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Missing required command: $command" >&2
    exit 2
  fi
done

new_id() {
  python3 -c 'import uuid; print(uuid.uuid4())'
}

pending_events() {
  curl -fsS "$LEDGER_METRICS" |
    awk '$1 == "ledger_outbox_pending" { print $2; found=1 }
         END { if (!found) exit 1 }'
}

echo "Day 12: checking observability endpoints"

curl -fsS "$LEDGER_METRICS" >/dev/null
curl -fsS "$PAYMENT_METRICS" >/dev/null

curl -fsS "$LEDGER_METRICS" |
  grep -q '^ledger_outbox_pending'

curl -fsS "$PAYMENT_METRICS" |
  grep -q '^payments_outbox_pending'

TARGETS=$(curl -fsS "$PROMETHEUS/api/v1/targets")

jq -e '
  [.data.activeTargets[]?
   | select(.labels.job == "ledger-core" or
            .labels.job == "payment-service")
   | select(.health == "up")]
  | length == 2
' <<< "$TARGETS" >/dev/null

echo "PASS: Prometheus collects both services."

if [[ "${1:-}" != "--chaos" ]]; then
  echo "Observability smoke test passed."
  echo "To run the optional Kafka outage test:"
  echo "LEDGERLY_CHAOS_ACK=yes bash scripts/day12_smoke.sh --chaos"
  exit 0
fi

if [[ "${LEDGERLY_CHAOS_ACK:-}" != "yes" ]]; then
  echo "Set LEDGERLY_CHAOS_ACK=yes to run the outage test." >&2
  exit 2
fi

echo "Checking the ledger outbox is empty before testing"

BASELINE=$(pending_events)

if [[ "$BASELINE" != "0.0" && "$BASELINE" != "0" ]]; then
  echo "Outbox already contains pending events. Investigate first." >&2
  exit 1
fi

create_account() {
  curl -fsS -X POST "$LEDGER/api/v1/accounts" \
    -H "Content-Type: application/json" \
    -d "{\"ownerRef\":\"day12-$(new_id)\",\"currency\":\"KZT\"}" |
    jq -er '.id'
}

SOURCE=$(create_account)
DESTINATION=$(create_account)

curl -fsS -X POST \
  "$LEDGER/api/v1/accounts/$SOURCE/deposits" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(new_id)" \
  -d '{"amountMinor":50000,"currency":"KZT"}' \
  >/dev/null

# Allow the deposit event to be published first.
for attempt in $(seq 1 30); do
  if [[ "$(pending_events)" == "0" ||
        "$(pending_events)" == "0.0" ]]; then
    break
  fi
  sleep 2
done

if [[ "$(pending_events)" != "0" &&
      "$(pending_events)" != "0.0" ]]; then
  echo "Deposit event did not drain before outage test." >&2
  exit 1
fi

echo "Stopping Kafka..."

"${COMPOSE[@]}" stop kafka

restore_kafka() {
  "${COMPOSE[@]}" start kafka >/dev/null 2>&1 || true
}

trap restore_kafka EXIT

echo "Creating a transfer with Kafka unavailable"

curl -fsS -X POST "$LEDGER/api/v1/transfers" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(new_id)" \
  -d "{
    \"fromAccountId\":\"$SOURCE\",
    \"toAccountId\":\"$DESTINATION\",
    \"amountMinor\":10000,
    \"currency\":\"KZT\"
  }" >/dev/null

echo "Checking that the outbox retained the event"

FOUND=false

for attempt in $(seq 1 20); do
  PENDING=$(pending_events)

  if awk "BEGIN { exit !($PENDING >= 1) }"; then
    FOUND=true
    break
  fi

  sleep 2
done

if [[ "$FOUND" != "true" ]]; then
  echo "FAIL: no pending outbox event detected." >&2
  exit 1
fi

echo "PASS: committed event retained during Kafka outage."

echo "Restarting Kafka..."

"${COMPOSE[@]}" start kafka

echo "Checking outbox recovery..."

for attempt in $(seq 1 90); do
  PENDING=$(pending_events)

  if awk "BEGIN { exit !($PENDING == 0) }"; then
    echo "PASS: outbox backlog drained after recovery."
    exit 0
  fi

  sleep 2
done

echo "FAIL: outbox did not drain after Kafka recovery." >&2
exit 1