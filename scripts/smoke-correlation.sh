#!/usr/bin/env bash
# Verify HTTP -> ledger outbox -> Kafka -> payment outbox correlation using fresh local accounts.
# Requires both applications and the default Compose PostgreSQL/Kafka services to be running.
set -euo pipefail
cd "$(dirname "$0")/.."

LEDGER="${LEDGER:-http://localhost:8080}"
PAYMENTS="${PAYMENTS:-http://localhost:8081}"
COMPOSE=(docker compose)
for dependency in curl jq python3 docker; do
  command -v "$dependency" >/dev/null || { echo "Missing dependency: $dependency" >&2; exit 2; }
done
work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT

new_id() { python3 -c 'import uuid; print(uuid.uuid4())'; }
require_id() {
  [[ "$1" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] || {
    echo "Expected a UUID in the service response" >&2; exit 1;
  }
}
expect() {
  [[ "$1" == "$2" ]] || { echo "FAIL: $3 (got '$1', expected '$2')" >&2; exit 1; }
}
echoed_id() {
  awk 'tolower($1) == "x-correlation-id:" {gsub(/\r/, "", $2); print $2}' "$work_dir/headers"
}
ledger_sql() {
  "${COMPOSE[@]}" exec -T postgres psql -X -At -v ON_ERROR_STOP=1 -U ledger -d ledger -c "$1"
}
payment_sql() {
  "${COMPOSE[@]}" exec -T postgres-payments psql -X -At -v ON_ERROR_STOP=1 -U payments -d payments -c "$1"
}
wait_status() {
  local payment_id=$1 expected=$2 observed=""
  for attempt in $(seq 1 60); do
    observed=$(curl -fsS "$PAYMENTS/api/v1/payments/$payment_id" | jq -er .status)
    [[ "$observed" == "$expected" ]] && return 0
    sleep 0.5
  done
  echo "FAIL: payment did not become $expected (last state: $observed)" >&2
  exit 1
}
payment_event_id() {
  payment_sql "SELECT correlation_id FROM outbox_events WHERE aggregate_id = '$1' AND payload->>'status' = '$2' ORDER BY seq DESC LIMIT 1"
}

curl -fsS "$LEDGER/actuator/health" >/dev/null
curl -fsS "$PAYMENTS/actuator/health" >/dev/null
owner="correlation-smoke-$(new_id)"
payer=$(curl -fsS -X POST "$LEDGER/api/v1/accounts" -H 'Content-Type: application/json' \
  -d "{\"ownerRef\":\"$owner-payer\",\"currency\":\"KZT\"}" | jq -er .id)
payee=$(curl -fsS -X POST "$LEDGER/api/v1/accounts" -H 'Content-Type: application/json' \
  -d "{\"ownerRef\":\"$owner-payee\",\"currency\":\"KZT\"}" | jq -er .id)
require_id "$payer"
require_id "$payee"
curl -fsS -X POST "$LEDGER/api/v1/accounts/$payer/deposits" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(new_id)" -d '{"amountMinor":100000,"currency":"KZT"}' >/dev/null

create_payment() {
  curl -fsS -D "$work_dir/headers" -X POST "$PAYMENTS/api/v1/payments" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $(new_id)" -H "X-Correlation-Id: $1" \
    -d "{\"fromAccountId\":\"$payer\",\"toAccountId\":\"$payee\",\"amountMinor\":10000,\"currency\":\"KZT\",\"captureMode\":\"$2\"}"
}

creation_id="payment-$(new_id)"
payment=$(create_payment "$creation_id" AUTOMATIC)
payment_id=$(jq -er .id <<< "$payment")
transaction_id=$(jq -er .ledgerTransactionId <<< "$payment")
require_id "$payment_id"
require_id "$transaction_id"
expect "$(echoed_id)" "$creation_id" 'payment response echoes the request ID'
expect "$(payment_event_id "$payment_id" COMPLETED)" "$creation_id" 'payment outbox retains the HTTP context'
expect "$(ledger_sql "SELECT correlation_id FROM outbox_events WHERE aggregate_id = '$transaction_id'")" \
  "$creation_id" 'ledger posting retains the forwarded HTTP context'
echo "PASS: payment HTTP -> ledger HTTP -> both outboxes ($creation_id)"

reversal_id="reversal-$(new_id)"
curl -fsS -D "$work_dir/headers" -X POST "$LEDGER/api/v1/transactions/$transaction_id/reversals" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(new_id)" -H "X-Correlation-Id: $reversal_id" \
  -d '{"reason":"correlation smoke test"}' >/dev/null
expect "$(echoed_id)" "$reversal_id" 'ledger response echoes the reversal request ID'
wait_status "$payment_id" REVERSED
expect "$(payment_event_id "$payment_id" REVERSED)" "$reversal_id" 'Kafka reversal handler preserves the originating request'
echo "PASS: ledger reversal -> Kafka -> payment status event ($reversal_id)"

authorization_id="authorization-$(new_id)"
manual=$(create_payment "$authorization_id" MANUAL)
manual_id=$(jq -er .id <<< "$manual")
require_id "$manual_id"
hold_id=$(payment_sql "SELECT ledger_hold_id FROM payments WHERE id = '$manual_id'")
require_id "$hold_id"
expect "$(payment_event_id "$manual_id" AUTHORIZED)" "$authorization_id" 'authorization event retains its request ID'

capture_id="capture-$(new_id)"
curl -fsS -D "$work_dir/headers" -X POST "$LEDGER/api/v1/holds/$hold_id/capture" \
  -H "Idempotency-Key: $(new_id)" -H "X-Correlation-Id: $capture_id" >/dev/null
expect "$(echoed_id)" "$capture_id" 'capture response echoes the request ID'
wait_status "$manual_id" COMPLETED
expect "$(ledger_sql "SELECT correlation_id FROM outbox_events WHERE aggregate_id = '$hold_id' AND event_type = 'HoldStatusChanged'")" \
  "$capture_id" 'deferred hold event retains the capture context'
expect "$(payment_event_id "$manual_id" COMPLETED)" "$capture_id" 'Kafka hold handler preserves the capture context'
echo "PASS: deferred hold event -> Kafka -> payment status event ($capture_id)"
echo 'Correlation smoke test passed. Fresh demo accounts and their ledger history remain for inspection.'
