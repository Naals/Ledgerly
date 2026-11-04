#!/usr/bin/env bash
# Authorize-then-capture and authorize-then-cancel, end to end through both services.
# Needs: docker compose up (ledger-core on :8080, payment-service on :8081), curl, sed.
set -euo pipefail

LEDGER="${LEDGER:-http://localhost:8080}"
PAYMENTS="${PAYMENTS:-http://localhost:8081}"
JSON='Content-Type: application/json'

key() { cat /proc/sys/kernel/random/uuid; }
field() { sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\)\"\{0,1\}.*/\1/p" | head -1; }
expect() { [ "$1" = "$2" ] && echo "ok    $3" || { echo "FAIL  $3: got '$1', expected '$2'"; exit 1; }; }
balance() { curl -sf "$LEDGER/api/v1/accounts/$1/balance"; }

owner="smoke-$(key)"
alice=$(curl -sf -X POST "$LEDGER/api/v1/accounts" -H "$JSON" -d "{\"ownerRef\":\"alice-$owner\",\"currency\":\"KZT\"}" | field id)
bob=$(curl -sf -X POST "$LEDGER/api/v1/accounts" -H "$JSON" -d "{\"ownerRef\":\"bob-$owner\",\"currency\":\"KZT\"}" | field id)
curl -sf -X POST "$LEDGER/api/v1/accounts/$alice/deposits" -H "$JSON" -H "Idempotency-Key: $(key)" \
  -d '{"amountMinor":1000000,"currency":"KZT"}' >/dev/null

pay() {  # amount
  curl -sf -X POST "$PAYMENTS/api/v1/payments" -H "$JSON" -H "Idempotency-Key: $(key)" \
    -d "{\"fromAccountId\":\"$alice\",\"toAccountId\":\"$bob\",\"amountMinor\":$1,\"currency\":\"KZT\",\"captureMode\":\"MANUAL\"}"
}

echo "== authorize, then capture"
payment=$(pay 300000)
id=$(echo "$payment" | field id)
expect "$(echo "$payment" | field status)" AUTHORIZED "the payment is authorized"
expect "$(balance "$alice" | field heldMinor)" 300000 "the ledger holds 300000 on alice"
expect "$(balance "$alice" | field availableMinor)" 700000 "alice can still spend 700000"
captured=$(curl -sf -X POST "$PAYMENTS/api/v1/payments/$id/capture")
expect "$(echo "$captured" | field status)" COMPLETED "the payment is completed"
expect "$(balance "$alice" | field amountMinor)" 700000 "alice paid 300000"
expect "$(balance "$alice" | field heldMinor)" 0 "nothing is held any more"
expect "$(balance "$bob" | field amountMinor)" 300000 "bob received 300000"
expect "$(curl -sf -X POST "$PAYMENTS/api/v1/payments/$id/capture" | field status)" COMPLETED "capturing again changes nothing"

echo "== authorize, then cancel"
id=$(pay 200000 | field id)
expect "$(balance "$alice" | field heldMinor)" 200000 "the ledger holds 200000"
expect "$(curl -sf -X POST "$PAYMENTS/api/v1/payments/$id/cancel" | field status)" CANCELLED "the payment is cancelled"
expect "$(balance "$alice" | field heldMinor)" 0 "the hold was released"
expect "$(balance "$alice" | field amountMinor)" 700000 "alice kept her money"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$PAYMENTS/api/v1/payments/$id/capture")
expect "$code" 409 "a cancelled payment cannot be captured"

echo "all good"