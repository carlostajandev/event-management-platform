#!/usr/bin/env bash
# ============================================================
# Event Management Platform — API Requests
# Uso: chmod +x api-requests.sh && ./api-requests.sh
#
# Prerrequisitos:
#   - La app corriendo en http://localhost:8080
#   - jq instalado (brew install jq / apt install jq)
# ============================================================

set -euo pipefail

BASE_URL="http://localhost:8080"
IDEMPOTENCY_KEY=$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)

# Colores
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

log_section() { echo -e "\n${BLUE}══════════════════════════════════════${NC}"; echo -e "${BLUE}  $1${NC}"; echo -e "${BLUE}══════════════════════════════════════${NC}"; }
log_ok()      { echo -e "${GREEN}  ✓ $1${NC}"; }
log_info()    { echo -e "${YELLOW}  → $1${NC}"; }
log_error()   { echo -e "${RED}  ✗ $1${NC}"; }

check_dependencies() {
    if ! command -v jq &> /dev/null; then
        log_error "jq no está instalado. Instalar con: brew install jq  o  apt install jq"
        exit 1
    fi
    if ! curl -sf "$BASE_URL/actuator/health" | jq -e '.status == "UP"' > /dev/null 2>&1; then
        log_error "La app no está corriendo en $BASE_URL"
        log_error "Ejecutar: ./mvnw spring-boot:run"
        exit 1
    fi
    log_ok "App corriendo en $BASE_URL"
}

# ────────────────────────────────────────────────────────────
# ACTUATOR
# ────────────────────────────────────────────────────────────
health_check() {
    log_section "ACTUATOR — Health Check"
    log_info "GET /actuator/health"

    RESPONSE=$(curl -sf "$BASE_URL/actuator/health")
    echo "$RESPONSE" | jq '.'
    STATUS=$(echo "$RESPONSE" | jq -r '.status')

    if [ "$STATUS" = "UP" ]; then
        log_ok "App status: $STATUS"
    else
        log_error "App status: $STATUS"
        exit 1
    fi
}

# ────────────────────────────────────────────────────────────
# EVENTS
# ────────────────────────────────────────────────────────────
create_event() {
    log_section "EVENT — Create Event"
    log_info "POST /api/v1/events"

    RESPONSE=$(curl -sf -X POST "$BASE_URL/api/v1/events" \
        -H "Content-Type: application/json" \
        -d '{
          "name": "Bad Bunny World Tour 2027",
          "description": "El concierto del año en Bogotá",
          "eventDate": "2027-06-15T20:00:00Z",
          "venueName": "Estadio El Campín",
          "venueCity": "Bogotá",
          "venueCountry": "Colombia",
          "totalCapacity": 50000,
          "ticketPrice": 350000,
          "currency": "COP"
        }')

    echo "$RESPONSE" | jq '.'
    EVENT_ID=$(echo "$RESPONSE" | jq -r '.eventId')
    export EVENT_ID
    log_ok "Event created: $EVENT_ID"
}

get_event() {
    log_section "EVENT — Get Event by ID"
    log_info "GET /api/v1/events/$EVENT_ID"

    curl -sf "$BASE_URL/api/v1/events/$EVENT_ID" | jq '.'
    log_ok "Event retrieved"
}

get_all_events() {
    log_section "EVENT — Get All Events"
    log_info "GET /api/v1/events"

    RESPONSE=$(curl -sf "$BASE_URL/api/v1/events")
    COUNT=$(echo "$RESPONSE" | jq 'length')
    echo "$RESPONSE" | jq '.[0]'
    log_ok "Events found: $COUNT"
}

get_availability() {
    log_section "EVENT — Get Availability"
    log_info "GET /api/v1/events/$EVENT_ID/availability"

    RESPONSE=$(curl -sf "$BASE_URL/api/v1/events/$EVENT_ID/availability")
    echo "$RESPONSE" | jq '.'
    AVAILABLE=$(echo "$RESPONSE" | jq '.availableTickets')
    log_ok "Available tickets: $AVAILABLE"
}

event_not_found() {
    log_section "EVENT — 404 Not Found"
    log_info "GET /api/v1/events/evt_does_not_exist"

    HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
        "$BASE_URL/api/v1/events/evt_does_not_exist")

    BODY=$(curl -sf "$BASE_URL/api/v1/events/evt_does_not_exist" 2>/dev/null || \
           curl -s "$BASE_URL/api/v1/events/evt_does_not_exist")
    echo "$BODY" | jq '.' 2>/dev/null || echo "$BODY"

    if [ "$HTTP_STATUS" = "404" ]; then
        log_ok "HTTP $HTTP_STATUS — EventNotFoundException mapped correctly"
    else
        log_error "Expected 404, got $HTTP_STATUS"
    fi
}

# ────────────────────────────────────────────────────────────
# ORDERS
# ────────────────────────────────────────────────────────────
reserve_tickets() {
    log_section "ORDER — Reserve Tickets"
    log_info "POST /api/v1/orders (X-Idempotency-Key: $IDEMPOTENCY_KEY)"

    RESPONSE=$(curl -sf -X POST "$BASE_URL/api/v1/orders" \
        -H "Content-Type: application/json" \
        -H "X-Idempotency-Key: $IDEMPOTENCY_KEY" \
        -d "{
          \"eventId\": \"$EVENT_ID\",
          \"userId\": \"usr_test_001\",
          \"quantity\": 2
        }")

    echo "$RESPONSE" | jq '.'
    ORDER_ID=$(echo "$RESPONSE" | jq -r '.orderId')
    EXPIRES_AT=$(echo "$RESPONSE" | jq -r '.expiresAt')
    export ORDER_ID
    log_ok "Order created: $ORDER_ID"
    log_info "Reservation expires at: $EXPIRES_AT"
}

reserve_tickets_idempotent() {
    log_section "ORDER — Idempotent Request (same key, should return cached response)"
    log_info "POST /api/v1/orders (same X-Idempotency-Key: $IDEMPOTENCY_KEY)"

    RESPONSE=$(curl -sf -X POST "$BASE_URL/api/v1/orders" \
        -H "Content-Type: application/json" \
        -H "X-Idempotency-Key: $IDEMPOTENCY_KEY" \
        -d "{
          \"eventId\": \"$EVENT_ID\",
          \"userId\": \"usr_test_001\",
          \"quantity\": 2
        }")

    echo "$RESPONSE" | jq '.'
    CACHED_ORDER_ID=$(echo "$RESPONSE" | jq -r '.orderId')

    if [ "$CACHED_ORDER_ID" = "$ORDER_ID" ]; then
        log_ok "Idempotency working — same orderId returned: $CACHED_ORDER_ID"
    else
        log_error "Idempotency FAILED — different orderId: $CACHED_ORDER_ID (expected: $ORDER_ID)"
    fi
}

reserve_missing_idempotency_key() {
    log_section "ORDER — 400 Missing X-Idempotency-Key"
    log_info "POST /api/v1/orders (without header)"

    HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "$BASE_URL/api/v1/orders" \
        -H "Content-Type: application/json" \
        -d "{\"eventId\": \"$EVENT_ID\", \"userId\": \"usr_test_001\", \"quantity\": 2}")

    if [ "$HTTP_STATUS" = "400" ]; then
        log_ok "HTTP $HTTP_STATUS — Missing header handled correctly"
    else
        log_error "Expected 400, got $HTTP_STATUS"
    fi
}

reserve_invalid_quantity() {
    log_section "ORDER — 400 Invalid Quantity (exceeds max 10)"
    log_info "POST /api/v1/orders (quantity: 99)"
    NEW_KEY=$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)

    HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "$BASE_URL/api/v1/orders" \
        -H "Content-Type: application/json" \
        -H "X-Idempotency-Key: $NEW_KEY" \
        -d "{\"eventId\": \"$EVENT_ID\", \"userId\": \"usr_test_001\", \"quantity\": 99}")

    if [ "$HTTP_STATUS" = "400" ]; then
        log_ok "HTTP $HTTP_STATUS — Validation working"
    else
        log_error "Expected 400, got $HTTP_STATUS"
    fi
}

get_order_status() {
    log_section "ORDER — Get Order Status"
    log_info "GET /api/v1/orders/$ORDER_ID"

    RESPONSE=$(curl -sf "$BASE_URL/api/v1/orders/$ORDER_ID")
    echo "$RESPONSE" | jq '.'
    STATUS=$(echo "$RESPONSE" | jq -r '.status')
    log_ok "Order status: $STATUS"
}

order_not_found() {
    log_section "ORDER — 404 Not Found"
    log_info "GET /api/v1/orders/ord_does_not_exist"

    HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
        "$BASE_URL/api/v1/orders/ord_does_not_exist")

    if [ "$HTTP_STATUS" = "404" ]; then
        log_ok "HTTP $HTTP_STATUS — OrderNotFoundException mapped correctly"
    else
        log_error "Expected 404, got $HTTP_STATUS"
    fi
}

# ────────────────────────────────────────────────────────────
# MAIN — Ejecuta todos los tests en orden
# ────────────────────────────────────────────────────────────
main() {
    echo -e "\n${BLUE}Event Management Platform — API Test Suite${NC}"
    echo -e "${BLUE}Base URL: $BASE_URL${NC}"
    echo -e "${BLUE}Idempotency Key: $IDEMPOTENCY_KEY${NC}\n"

    check_dependencies

    # Actuator
    health_check

    # Events
    create_event
    get_event
    get_all_events
    get_availability
    event_not_found

    # Orders
    reserve_tickets
    reserve_tickets_idempotent
    reserve_missing_idempotency_key
    reserve_invalid_quantity
    get_order_status
    order_not_found

    log_section "RESUMEN"
    log_ok "Todos los tests completados"
    log_info "Event ID: $EVENT_ID"
    log_info "Order ID: $ORDER_ID"
    log_info "Verificar DynamoDB Admin: http://localhost:8001"
    log_info "Verificar métricas Prometheus: $BASE_URL/actuator/prometheus"
}

main "$@"
