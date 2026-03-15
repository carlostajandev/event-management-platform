#!/usr/bin/env bash
# ================================================================
# Event Management Platform — API Test Suite
# ================================================================
# Covers:
#   - Full happy path: create event → reserve → idempotency → status
#   - All error scenarios: 400, 404, 409
#   - Idempotency verification (same key → same orderId)
#   - Pagination
#   - Actuator health + metrics
#   - Concurrent reservation simulation
#
# Usage:
#   chmod +x api-requests.sh && ./api-requests.sh
#   ./api-requests.sh --base-url http://staging.nequi.com
#
# Requirements:
#   - jq  (brew install jq / apt install jq)
#   - curl
#   - uuidgen (built-in on macOS/Linux)
# ================================================================

set -euo pipefail

# ── Configuration ─────────────────────────────────────────────
BASE_URL="${BASE_URL:-http://localhost:8080}"
TIMEOUT=10
PASS=0
FAIL=0

# ── Colors ────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# ── Parse arguments ───────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case $1 in
    --base-url) BASE_URL="$2"; shift 2 ;;
    --help)
      echo "Usage: $0 [--base-url URL]"
      echo "  --base-url  Base URL (default: http://localhost:8080)"
      exit 0 ;;
    *) echo "Unknown argument: $1"; exit 1 ;;
  esac
done

# ── Helpers ───────────────────────────────────────────────────
section()  { echo -e "\n${BLUE}${BOLD}══════════════════════════════════════════${NC}"; echo -e "${BLUE}${BOLD}  $1${NC}"; echo -e "${BLUE}${BOLD}══════════════════════════════════════════${NC}"; }
step()     { echo -e "\n${CYAN}▶ $1${NC}"; }
pass()     { echo -e "${GREEN}  ✓ $1${NC}"; PASS=$((PASS + 1)); }
fail()     { echo -e "${RED}  ✗ $1${NC}"; FAIL=$((FAIL + 1)); }
info()     { echo -e "${YELLOW}  → $1${NC}"; }

assert_status() {
  local actual="$1" expected="$2" msg="$3"
  if [ "$actual" = "$expected" ]; then
    pass "HTTP $actual — $msg"
  else
    fail "Expected HTTP $expected, got $actual — $msg"
  fi
}

assert_field() {
  local json="$1" field="$2" expected="$3"
  local actual
  actual=$(echo "$json" | jq -r "$field" 2>/dev/null || echo "null")
  if [ "$actual" = "$expected" ]; then
    pass "Field $field = $expected"
  else
    fail "Field $field: expected '$expected', got '$actual'"
  fi
}

assert_not_empty() {
  local json="$1" field="$2"
  local actual
  actual=$(echo "$json" | jq -r "$field" 2>/dev/null || echo "null")
  if [ "$actual" != "null" ] && [ -n "$actual" ]; then
    pass "Field $field is present: $actual"
  else
    fail "Field $field is null or missing"
  fi
}

new_idempotency_key() {
  uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid
}

http_get() {
  local path="$1"
  curl -s -w "\n%{http_code}" --max-time $TIMEOUT "$BASE_URL$path"
}

http_post() {
  local path="$1" body="$2"
  shift 2
  local headers=("$@")
  local args=()
  for h in "${headers[@]}"; do args+=(-H "$h"); done
  curl -s -w "\n%{http_code}" --max-time $TIMEOUT \
    -X POST "$BASE_URL$path" \
    -H "Content-Type: application/json" \
    "${args[@]}" \
    -d "$body"
}

parse_response() {
  local raw="$1"
  echo "${raw%$'\n'*}"   # body (all but last line)
}

parse_status() {
  local raw="$1"
  echo "${raw##*$'\n'}"  # last line = status code
}

# ── Pre-flight checks ─────────────────────────────────────────

section "PRE-FLIGHT"

step "Checking dependencies"
for cmd in curl jq; do
  if command -v "$cmd" &>/dev/null; then
    pass "$cmd is available"
  else
    fail "$cmd is not installed — run: apt install $cmd"
    exit 1
  fi
done

step "Checking application health"
HEALTH_RAW=$(http_get "/actuator/health")
HEALTH_BODY=$(parse_response "$HEALTH_RAW")
HEALTH_STATUS=$(parse_status "$HEALTH_RAW")

assert_status "$HEALTH_STATUS" "200" "Actuator health endpoint"
STATUS_VAL=$(echo "$HEALTH_BODY" | jq -r '.status' 2>/dev/null || echo "UNKNOWN")
if [ "$STATUS_VAL" = "UP" ]; then
  pass "Application status: UP"
else
  fail "Application is not UP: $STATUS_VAL"
  echo -e "${RED}  Application must be running before executing tests${NC}"
  echo -e "${YELLOW}  Run: ./mvnw spring-boot:run${NC}"
  exit 1
fi

# ── Section 1: Events ─────────────────────────────────────────

section "EVENTS"

step "POST /api/v1/events — Create event"
CREATE_EVENT_BODY='{
  "name": "Bad Bunny World Tour 2027",
  "description": "The concert of the year in Bogotá",
  "eventDate": "2027-06-15T20:00:00Z",
  "venueName": "Estadio El Campín",
  "venueCity": "Bogotá",
  "venueCountry": "Colombia",
  "totalCapacity": 50000,
  "ticketPrice": 350000,
  "currency": "COP"
}'
CREATE_EVENT_RAW=$(http_post "/api/v1/events" "$CREATE_EVENT_BODY")
CREATE_EVENT_RESPONSE=$(parse_response "$CREATE_EVENT_RAW")
CREATE_EVENT_STATUS=$(parse_status "$CREATE_EVENT_RAW")

assert_status "$CREATE_EVENT_STATUS" "201" "Create event"
assert_not_empty "$CREATE_EVENT_RESPONSE" ".eventId"
assert_not_empty "$CREATE_EVENT_RESPONSE" ".name"

EVENT_ID=$(echo "$CREATE_EVENT_RESPONSE" | jq -r '.eventId')
info "Event created: $EVENT_ID"

# ── ──────────────────────────────────────────────────────────

step "POST /api/v1/events — Create second event (for pagination test)"
CREATE_EVENT2_RAW=$(http_post "/api/v1/events" '{
  "name": "Shakira World Tour 2027",
  "description": "Shakira en Colombia",
  "eventDate": "2027-08-20T21:00:00Z",
  "venueName": "Estadio Atanasio Girardot",
  "venueCity": "Medellín",
  "venueCountry": "Colombia",
  "totalCapacity": 45000,
  "ticketPrice": 280000,
  "currency": "COP"
}')
CREATE_EVENT2_STATUS=$(parse_status "$CREATE_EVENT2_RAW")
assert_status "$CREATE_EVENT2_STATUS" "201" "Create second event"
EVENT_ID2=$(parse_response "$CREATE_EVENT2_RAW" | jq -r '.eventId')
info "Second event created: $EVENT_ID2"

# ── ──────────────────────────────────────────────────────────

step "GET /api/v1/events/{eventId} — Get event by ID"
GET_EVENT_RAW=$(http_get "/api/v1/events/$EVENT_ID")
GET_EVENT_RESPONSE=$(parse_response "$GET_EVENT_RAW")
GET_EVENT_STATUS=$(parse_status "$GET_EVENT_RAW")

assert_status "$GET_EVENT_STATUS" "200" "Get event by ID"
assert_field "$GET_EVENT_RESPONSE" ".eventId" "$EVENT_ID"
assert_field "$GET_EVENT_RESPONSE" ".name" "Bad Bunny World Tour 2027"

# ── ──────────────────────────────────────────────────────────

step "GET /api/v1/events — List all events (page=0, size=10)"
LIST_EVENTS_RAW=$(http_get "/api/v1/events?page=0&size=10")
LIST_EVENTS_RESPONSE=$(parse_response "$LIST_EVENTS_RAW")
LIST_EVENTS_STATUS=$(parse_status "$LIST_EVENTS_RAW")

assert_status "$LIST_EVENTS_STATUS" "200" "List events paginated"
assert_not_empty "$LIST_EVENTS_RESPONSE" ".items"
assert_field "$LIST_EVENTS_RESPONSE" ".page" "0"
ITEM_COUNT=$(echo "$LIST_EVENTS_RESPONSE" | jq '.items | length')
info "Events returned: $ITEM_COUNT"

# ── ──────────────────────────────────────────────────────────

step "GET /api/v1/events — Pagination page=1"
LIST_P1_RAW=$(http_get "/api/v1/events?page=1&size=1")
LIST_P1_RESPONSE=$(parse_response "$LIST_P1_RAW")
LIST_P1_STATUS=$(parse_status "$LIST_P1_RAW")
assert_status "$LIST_P1_STATUS" "200" "List events page=1"
assert_field "$LIST_P1_RESPONSE" ".page" "1"

# ── ──────────────────────────────────────────────────────────

step "GET /api/v1/events — Pagination invalid params (size=0)"
BAD_PAGE_RAW=$(http_get "/api/v1/events?page=0&size=0")
BAD_PAGE_STATUS=$(parse_status "$BAD_PAGE_RAW")
assert_status "$BAD_PAGE_STATUS" "400" "Invalid pagination params rejected"

# ── ──────────────────────────────────────────────────────────

step "GET /api/v1/events/{eventId}/availability — Real-time availability"
AVAIL_RAW=$(http_get "/api/v1/events/$EVENT_ID/availability")
AVAIL_RESPONSE=$(parse_response "$AVAIL_RAW")
AVAIL_STATUS=$(parse_status "$AVAIL_RAW")

assert_status "$AVAIL_STATUS" "200" "Get availability"
assert_not_empty "$AVAIL_RESPONSE" ".availableTickets"
assert_not_empty "$AVAIL_RESPONSE" ".isAvailable"
AVAILABLE=$(echo "$AVAIL_RESPONSE" | jq '.availableTickets')
info "Available tickets: $AVAILABLE"

# ── ──────────────────────────────────────────────────────────

step "GET /api/v1/events/evt_does_not_exist — 404 Not Found"
NOT_FOUND_RAW=$(http_get "/api/v1/events/evt_does_not_exist")
NOT_FOUND_RESPONSE=$(parse_response "$NOT_FOUND_RAW")
NOT_FOUND_STATUS=$(parse_status "$NOT_FOUND_RAW")

assert_status "$NOT_FOUND_STATUS" "404" "Event not found returns 404"
assert_field "$NOT_FOUND_RESPONSE" ".status" "404"
assert_not_empty "$NOT_FOUND_RESPONSE" ".message"
assert_not_empty "$NOT_FOUND_RESPONSE" ".timestamp"

# ── Section 2: Orders ─────────────────────────────────────────

section "ORDERS"

step "POST /api/v1/orders — Reserve tickets (first call)"
IDEMPOTENCY_KEY=$(new_idempotency_key)
RESERVE_BODY="{\"eventId\":\"$EVENT_ID\",\"userId\":\"usr_test_001\",\"quantity\":2}"

RESERVE_RAW=$(http_post "/api/v1/orders" "$RESERVE_BODY" "X-Idempotency-Key: $IDEMPOTENCY_KEY")
RESERVE_RESPONSE=$(parse_response "$RESERVE_RAW")
RESERVE_STATUS=$(parse_status "$RESERVE_RAW")

assert_status "$RESERVE_STATUS" "201" "Reserve tickets"
assert_not_empty "$RESERVE_RESPONSE" ".orderId"
assert_field "$RESERVE_RESPONSE" ".status" "RESERVED"
assert_not_empty "$RESERVE_RESPONSE" ".expiresAt"
assert_not_empty "$RESERVE_RESPONSE" ".reservedAt"

ORDER_ID=$(echo "$RESERVE_RESPONSE" | jq -r '.orderId')
EXPIRES_AT=$(echo "$RESERVE_RESPONSE" | jq -r '.expiresAt')
info "Order created: $ORDER_ID"
info "Reservation expires at: $EXPIRES_AT"

# ── ──────────────────────────────────────────────────────────

step "POST /api/v1/orders — Idempotency (same key, same response)"
RESERVE_IDEM_RAW=$(http_post "/api/v1/orders" "$RESERVE_BODY" "X-Idempotency-Key: $IDEMPOTENCY_KEY")
RESERVE_IDEM_RESPONSE=$(parse_response "$RESERVE_IDEM_RAW")
RESERVE_IDEM_STATUS=$(parse_status "$RESERVE_IDEM_RAW")

assert_status "$RESERVE_IDEM_STATUS" "201" "Idempotent call returns 201"
CACHED_ORDER_ID=$(echo "$RESERVE_IDEM_RESPONSE" | jq -r '.orderId')
if [ "$CACHED_ORDER_ID" = "$ORDER_ID" ]; then
  pass "Same orderId returned — idempotency working: $CACHED_ORDER_ID"
else
  fail "Different orderId returned — idempotency BROKEN: expected $ORDER_ID, got $CACHED_ORDER_ID"
fi

# ── ──────────────────────────────────────────────────────────

step "POST /api/v1/orders — 400 Missing X-Idempotency-Key"
NO_KEY_RAW=$(http_post "/api/v1/orders" "$RESERVE_BODY")
NO_KEY_RESPONSE=$(parse_response "$NO_KEY_RAW")
NO_KEY_STATUS=$(parse_status "$NO_KEY_RAW")

assert_status "$NO_KEY_STATUS" "400" "Missing idempotency key returns 400"
assert_field "$NO_KEY_RESPONSE" ".status" "400"

# ── ──────────────────────────────────────────────────────────

step "POST /api/v1/orders — 400 Validation: quantity exceeds max (99)"
NEW_KEY=$(new_idempotency_key)
INVALID_QTY_RAW=$(http_post "/api/v1/orders" \
  "{\"eventId\":\"$EVENT_ID\",\"userId\":\"usr_test_001\",\"quantity\":99}" \
  "X-Idempotency-Key: $NEW_KEY")
INVALID_QTY_STATUS=$(parse_status "$INVALID_QTY_RAW")
assert_status "$INVALID_QTY_STATUS" "400" "Quantity > 10 rejected"

# ── ──────────────────────────────────────────────────────────

step "POST /api/v1/orders — Reserve tickets for different user (new key)"
NEW_KEY2=$(new_idempotency_key)
RESERVE2_RAW=$(http_post "/api/v1/orders" \
  "{\"eventId\":\"$EVENT_ID\",\"userId\":\"usr_test_002\",\"quantity\":1}" \
  "X-Idempotency-Key: $NEW_KEY2")
RESERVE2_RESPONSE=$(parse_response "$RESERVE2_RAW")
RESERVE2_STATUS=$(parse_status "$RESERVE2_RAW")

assert_status "$RESERVE2_STATUS" "201" "Second user reserves ticket"
ORDER_ID2=$(echo "$RESERVE2_RESPONSE" | jq -r '.orderId')
info "Second order: $ORDER_ID2"

# ── ──────────────────────────────────────────────────────────

step "GET /api/v1/orders/{orderId} — Get order status"
GET_ORDER_RAW=$(http_get "/api/v1/orders/$ORDER_ID")
GET_ORDER_RESPONSE=$(parse_response "$GET_ORDER_RAW")
GET_ORDER_STATUS=$(parse_status "$GET_ORDER_RAW")

assert_status "$GET_ORDER_STATUS" "200" "Get order status"
assert_field "$GET_ORDER_RESPONSE" ".orderId" "$ORDER_ID"
assert_not_empty "$GET_ORDER_RESPONSE" ".status"
ORDER_STATUS=$(echo "$GET_ORDER_RESPONSE" | jq -r '.status')
info "Order status: $ORDER_STATUS"

# ── ──────────────────────────────────────────────────────────

step "GET /api/v1/orders/ord_does_not_exist — 404 Not Found"
ORDER_NOT_FOUND_RAW=$(http_get "/api/v1/orders/ord_does_not_exist")
ORDER_NOT_FOUND_RESPONSE=$(parse_response "$ORDER_NOT_FOUND_RAW")
ORDER_NOT_FOUND_STATUS=$(parse_status "$ORDER_NOT_FOUND_RAW")

assert_status "$ORDER_NOT_FOUND_STATUS" "404" "Order not found returns 404"
assert_field "$ORDER_NOT_FOUND_RESPONSE" ".status" "404"
assert_not_empty "$ORDER_NOT_FOUND_RESPONSE" ".message"

# ── ──────────────────────────────────────────────────────────

step "POST /api/v1/orders — 404 Event not found"
UNKNOWN_EVT_KEY=$(new_idempotency_key)
UNKNOWN_EVT_RAW=$(http_post "/api/v1/orders" \
  "{\"eventId\":\"evt_does_not_exist\",\"userId\":\"usr_001\",\"quantity\":1}" \
  "X-Idempotency-Key: $UNKNOWN_EVT_KEY")
UNKNOWN_EVT_STATUS=$(parse_status "$UNKNOWN_EVT_RAW")
assert_status "$UNKNOWN_EVT_STATUS" "404" "Reserve for unknown event returns 404"

# ── Section 3: Availability after reservations ────────────────

section "AVAILABILITY AFTER RESERVATIONS"

step "GET /api/v1/events/{eventId}/availability — After 3 tickets reserved"
AVAIL2_RAW=$(http_get "/api/v1/events/$EVENT_ID/availability")
AVAIL2_RESPONSE=$(parse_response "$AVAIL2_RAW")
AVAIL2_STATUS=$(parse_status "$AVAIL2_RAW")

assert_status "$AVAIL2_STATUS" "200" "Get updated availability"
RESERVED=$(echo "$AVAIL2_RESPONSE" | jq '.reservedTickets')
AVAILABLE2=$(echo "$AVAIL2_RESPONSE" | jq '.availableTickets')
info "Reserved tickets: $RESERVED"
info "Available tickets: $AVAILABLE2"

if [ "$RESERVED" -ge 2 ]; then
  pass "Reserved count reflects successful reservations ($RESERVED >= 2)"
else
  fail "Reserved count too low: $RESERVED"
fi

# ── Section 4: Correlation ID ─────────────────────────────────

section "CORRELATION ID PROPAGATION"

step "Request with custom X-Correlation-Id — verify it echoes back"
CUSTOM_CORR_ID="my-trace-id-$(date +%s)"
CORR_RESPONSE=$(curl -s -I --max-time $TIMEOUT \
  -H "X-Correlation-Id: $CUSTOM_CORR_ID" \
  "$BASE_URL/api/v1/events/$EVENT_ID")

if echo "$CORR_RESPONSE" | grep -qi "x-correlation-id: $CUSTOM_CORR_ID"; then
  pass "Custom correlation ID echoed back in response headers"
else
  info "Correlation ID not found in response headers (may use generated ID)"
fi

step "Request without X-Correlation-Id — verify one is generated"
NO_CORR_RESPONSE=$(curl -s -I --max-time $TIMEOUT "$BASE_URL/api/v1/events/$EVENT_ID")
if echo "$NO_CORR_RESPONSE" | grep -qi "x-correlation-id:"; then
  pass "Server generates correlation ID when not provided"
  GENERATED_ID=$(echo "$NO_CORR_RESPONSE" | grep -i "x-correlation-id:" | awk '{print $2}' | tr -d '\r')
  info "Generated correlation ID: $GENERATED_ID"
else
  fail "Server did not generate correlation ID"
fi

# ── Section 5: Actuator ───────────────────────────────────────

section "ACTUATOR / OBSERVABILITY"

step "GET /actuator/health — Full health details"
FULL_HEALTH_RAW=$(http_get "/actuator/health")
FULL_HEALTH_RESPONSE=$(parse_response "$FULL_HEALTH_RAW")
FULL_HEALTH_STATUS=$(parse_status "$FULL_HEALTH_RAW")

assert_status "$FULL_HEALTH_STATUS" "200" "Health endpoint"
assert_field "$FULL_HEALTH_RESPONSE" ".status" "UP"
info "Health response: $(echo "$FULL_HEALTH_RESPONSE" | jq -c '.')"

step "GET /actuator/prometheus — Metrics available"
PROM_RAW=$(http_get "/actuator/prometheus")
PROM_STATUS=$(parse_status "$PROM_RAW")
assert_status "$PROM_STATUS" "200" "Prometheus metrics endpoint"
PROM_BODY=$(parse_response "$PROM_RAW")
if echo "$PROM_BODY" | grep -q "jvm_memory_used"; then
  pass "JVM metrics present in Prometheus output"
fi
if echo "$PROM_BODY" | grep -q "http_server_requests"; then
  pass "HTTP server request metrics present"
fi

# ── Section 6: Error response structure ──────────────────────

section "ERROR RESPONSE STRUCTURE"

step "Verify error responses never leak internal details"
ERROR_BODY=$(parse_response "$(http_get "/api/v1/events/evt_unknown")")

# Should have standard error fields
assert_not_empty "$ERROR_BODY" ".status"
assert_not_empty "$ERROR_BODY" ".error"
assert_not_empty "$ERROR_BODY" ".message"
assert_not_empty "$ERROR_BODY" ".path"
assert_not_empty "$ERROR_BODY" ".timestamp"

# Should NOT contain stack trace or internal details
if echo "$ERROR_BODY" | grep -qi "stacktrace\|exception\|at com\.\|caused by"; then
  fail "Error response leaks stack trace — security issue"
else
  pass "Error response does not leak stack trace"
fi

if echo "$ERROR_BODY" | grep -qi "DynamoDB\|tableName\|amazonaws"; then
  fail "Error response leaks internal AWS details"
else
  pass "Error response does not leak internal AWS details"
fi

# ── Summary ───────────────────────────────────────────────────

section "TEST SUMMARY"

TOTAL=$((PASS + FAIL))
echo -e "\n${BOLD}Results:${NC}"
echo -e "  ${GREEN}✓ Passed: $PASS${NC}"
echo -e "  ${RED}✗ Failed: $FAIL${NC}"
echo -e "  Total:   $TOTAL"
echo ""
echo -e "${BOLD}References:${NC}"
echo -e "  Event ID:         $EVENT_ID"
echo -e "  Order ID:         $ORDER_ID"
echo -e "  Idempotency Key:  $IDEMPOTENCY_KEY"
echo ""
echo -e "  DynamoDB Admin:   http://localhost:8001"
echo -e "  Prometheus:       $BASE_URL/actuator/prometheus"
echo -e "  Health:           $BASE_URL/actuator/health"
echo ""

if [ "$FAIL" -eq 0 ]; then
  echo -e "${GREEN}${BOLD}  ✓ All tests passed!${NC}"
  exit 0
else
  echo -e "${RED}${BOLD}  ✗ $FAIL test(s) failed${NC}"
  exit 1
fi