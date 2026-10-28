#!/usr/bin/env bash
# Walks through the main flows with curl against a running stack (docker compose up -d --wait).
# Usage: requests/demo.sh [base-url]        Only needs curl.
set -euo pipefail

BASE="${1:-http://localhost:8080}/api/v1"
RUN=$(date +%s)
JSON='Content-Type: application/json'

step() { printf '\n\033[1m== %s\033[0m\n' "$1"; }
field() { sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p"; }
call() { curl -sS -w '\n(HTTP %{http_code})\n' "$@"; }

step "Create an event with 3 tickets (admin)"
EVENT=$(curl -sS -X POST "$BASE/events" -H "$JSON" -H 'X-User-Role: admin' -H "Idempotency-Key: event-$RUN" \
  -d '{"name":"Rock Fest","venue":"Movistar Arena","date":"2027-03-15T20:00:00Z","capacity":3,"ticketPrice":150000,"currency":"COP"}')
echo "$EVENT"
EVENT_ID=$(echo "$EVENT" | field id)

step "Availability"
call "$BASE/events/$EVENT_ID/availability"

step "Ana holds 2 tickets: 202, RESERVED, queued for the worker"
ORDER=$(curl -sS -X POST "$BASE/orders" -H "$JSON" -H 'X-User-Id: ana' -H "Idempotency-Key: order-$RUN" \
  -d "{\"eventId\":\"$EVENT_ID\",\"quantity\":2}")
echo "$ORDER"
ORDER_ID=$(echo "$ORDER" | field orderId)

step "Ana's client retries with the same key: same order, nothing new held"
call -X POST "$BASE/orders" -H "$JSON" -H 'X-User-Id: ana' -H "Idempotency-Key: order-$RUN" \
  -d "{\"eventId\":\"$EVENT_ID\",\"quantity\":2}"

step "Same key with a different body: 422"
call -X POST "$BASE/orders" -H "$JSON" -H 'X-User-Id: ana' -H "Idempotency-Key: order-$RUN" \
  -d "{\"eventId\":\"$EVENT_ID\",\"quantity\":1}"

step "Order status once the worker processed it: PENDING_CONFIRMATION"
for _ in $(seq 1 20); do
  STATUS=$(curl -sS "$BASE/orders/$ORDER_ID" -H 'X-User-Id: ana' | field status)
  [ "$STATUS" != "RESERVED" ] && break
  sleep 0.25
done
call "$BASE/orders/$ORDER_ID" -H 'X-User-Id: ana'

step "Another user asks for Ana's order: 404"
call "$BASE/orders/$ORDER_ID" -H 'X-User-Id: luis'

step "Ana confirms (simulated payment): SOLD"
call -X POST "$BASE/orders/$ORDER_ID/confirm" -H 'X-User-Id: ana'

step "Complimentary ticket for the press (admin): COMPLIMENTARY"
call -X POST "$BASE/events/$EVENT_ID/complimentary" -H "$JSON" -H 'X-User-Role: admin' -H "Idempotency-Key: vip-$RUN" \
  -d '{"recipientUserId":"press-01","quantity":1}'

step "Luis tries to buy: 409 SOLD_OUT"
call -X POST "$BASE/orders" -H "$JSON" -H 'X-User-Id: luis' -H "Idempotency-Key: luis-$RUN" \
  -d "{\"eventId\":\"$EVENT_ID\",\"quantity\":1}"

step "Availability after the sale: 2 sold, 1 complimentary, 0 available"
call "$BASE/events/$EVENT_ID/availability"

step "Rejected: no caller identity (401), not admin (403), 7 tickets (400)"
call -X POST "$BASE/orders" -H "$JSON" -H "Idempotency-Key: anon-$RUN" -d "{\"eventId\":\"$EVENT_ID\",\"quantity\":1}"
call -X POST "$BASE/events" -H "$JSON" -H "Idempotency-Key: x-$RUN" \
  -d '{"name":"X","venue":"Y","date":"2027-03-15T20:00:00Z","capacity":10,"ticketPrice":1000,"currency":"COP"}'
call -X POST "$BASE/orders" -H "$JSON" -H 'X-User-Id: ana' -H "Idempotency-Key: big-$RUN" -d "{\"eventId\":\"$EVENT_ID\",\"quantity\":7}"
