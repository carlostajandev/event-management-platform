#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════
# Event Management Platform v2 — Full API Flow (Microservices)
# Covers: create event → reserve → order → idempotency → status
#
# Usage: ./api-requests.sh
# Requires: curl, jq
# ════════════════════════════════════════════════════════════════

set -e

EVENT_SVC="http://localhost:8081"
RESERVATION_SVC="http://localhost:8082"
ORDER_SVC="http://localhost:8083"

CORRELATION_ID=$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)
IDEMPOTENCY_KEY=$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)

echo "======================================"
echo " Event Management Platform v2 — Demo"
echo " Microservices: 4 services, DynamoDB, SQS"
echo "======================================"
echo ""

# ── 1. Create Event ───────────────────────────────────────────────
echo "[1] Creating event..."
EVENT_RESPONSE=$(curl -s -X POST "$EVENT_SVC/api/v1/events" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: $CORRELATION_ID" \
  -d '{
    "name": "Rock Fest Bogotá 2026",
    "description": "The biggest rock festival in Colombia",
    "venue": {
      "name": "Movistar Arena",
      "address": "Cra 37 #27-40",
      "city": "Bogotá",
      "country": "Colombia",
      "capacity": 5000
    },
    "eventDate": "2026-12-15T20:00:00Z",
    "ticketPrice": 150000.00,
    "currency": "COP",
    "totalCapacity": 10
  }')

echo "$EVENT_RESPONSE" | jq '{id, name, availableCount, status}'
EVENT_ID=$(echo "$EVENT_RESPONSE" | jq -r '.id // empty')
echo "Event ID: $EVENT_ID"
echo ""

# ── 2. Check Availability ─────────────────────────────────────────
echo "[2] Checking availability..."
curl -s "$EVENT_SVC/api/v1/events/$EVENT_ID/availability" | jq .
echo ""

# ── 3. Reserve Tickets (atomic conditional write) ─────────────────
echo "[3] Reserving 2 tickets (conditional write: availableCount >= 2 AND version = 0)..."
RESERVATION_RESPONSE=$(curl -s -X POST "$RESERVATION_SVC/api/v1/reservations" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: $CORRELATION_ID" \
  -d "{
    \"eventId\": \"$EVENT_ID\",
    \"userId\": \"user-demo-001\",
    \"seatsCount\": 2
  }")

echo "$RESERVATION_RESPONSE" | jq '{id, status, seatsCount, expiresAt}'
RESERVATION_ID=$(echo "$RESERVATION_RESPONSE" | jq -r '.id // empty')
echo "Reservation ID: $RESERVATION_ID"
echo ""

# ── 4. Create Order (idempotent) ──────────────────────────────────
echo "[4] Creating order (TransactWriteItems: order + outbox, idempotency key: ${IDEMPOTENCY_KEY:0:8}...)..."
ORDER_RESPONSE=$(curl -s -X POST "$ORDER_SVC/api/v1/orders" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: $CORRELATION_ID" \
  -H "X-Idempotency-Key: $IDEMPOTENCY_KEY" \
  -d "{
    \"reservationId\": \"$RESERVATION_ID\",
    \"userId\": \"user-demo-001\"
  }")

echo "$ORDER_RESPONSE" | jq '{id, status, totalAmount, currency}'
ORDER_ID=$(echo "$ORDER_RESPONSE" | jq -r '.id // empty')
echo "Order ID: $ORDER_ID"
echo ""

# ── 5. Test Idempotency ───────────────────────────────────────────
echo "[5] Sending DUPLICATE request with same idempotency key..."
ORDER_DUP=$(curl -s -X POST "$ORDER_SVC/api/v1/orders" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: $IDEMPOTENCY_KEY" \
  -d "{
    \"reservationId\": \"$RESERVATION_ID\",
    \"userId\": \"user-demo-001\"
  }")

DUP_ID=$(echo "$ORDER_DUP" | jq -r '.id // empty')
if [ "$ORDER_ID" = "$DUP_ID" ]; then
  echo "IDEMPOTENCY: PASS — same orderId returned: $DUP_ID"
else
  echo "IDEMPOTENCY: FAIL — got different orderId: $DUP_ID"
fi
echo ""

# ── 6. Wait for async processing ─────────────────────────────────
echo "[6] Waiting 8s for consumer-service to process order via SQS..."
sleep 8

# ── 7. Check Order Status ─────────────────────────────────────────
echo "[7] Order status after async processing..."
curl -s "$ORDER_SVC/api/v1/orders/$ORDER_ID" | jq '{status, updatedAt}'
echo ""

# ── 8. Check Availability After Reservation ───────────────────────
echo "[8] Availability after 2 tickets reserved (should be 8)..."
curl -s "$EVENT_SVC/api/v1/events/$EVENT_ID/availability" | jq '{availableCount, available}'
echo ""

# ── 9. Oversell prevention test ───────────────────────────────────
echo "[9] Oversell prevention: 15 concurrent requests for 8 remaining tickets..."
PIDS=()
for i in $(seq 1 15); do
  curl -s -o "/tmp/reserve_$i.json" -w "%{http_code}" \
    -X POST "$RESERVATION_SVC/api/v1/reservations" \
    -H "Content-Type: application/json" \
    -d "{\"eventId\": \"$EVENT_ID\", \"userId\": \"concurrent-$i\", \"seatsCount\": 1}" \
    > "/tmp/status_$i.txt" 2>/dev/null &
  PIDS+=($!)
done

for pid in "${PIDS[@]}"; do wait "$pid" 2>/dev/null || true; done

SUCCESS_COUNT=$(grep -l "^201" /tmp/status_*.txt 2>/dev/null | wc -l)
CONFLICT_COUNT=$(grep -l "^409" /tmp/status_*.txt 2>/dev/null | wc -l)
echo "Results: $SUCCESS_COUNT succeeded (HTTP 201), $CONFLICT_COUNT got 409 Conflict"
echo "Expected: ≤8 successes, ≥7 conflicts (no oversell)"
rm -f /tmp/reserve_*.json /tmp/status_*.txt
echo ""

echo "======================================"
echo " Flow complete!"
echo ""
echo " Endpoints:"
echo "   event-service:       $EVENT_SVC"
echo "   reservation-service: $RESERVATION_SVC"
echo "   order-service:       $ORDER_SVC"
echo ""
echo " Observability:"
echo "   Grafana:    http://localhost:3000 (admin/admin)"
echo "   Prometheus: http://localhost:9090"
echo "======================================"
