# Ticket Reservation — Complete Flow Diagram

## Happy Path

```
POST /api/v1/orders
Headers: Content-Type: application/json
         X-Idempotency-Key: {uuid}
Body:    { eventId, userId, quantity }
         │
         ▼
┌─────────────────────────────────────────────────┐
│            CorrelationIdFilter                  │
│  Set X-Correlation-Id in MDC (generate if none) │
└─────────────────────────┬───────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────┐
│               OrderHandler                      │
│  1. Extract X-Idempotency-Key header            │
│  2. If missing → 400 Bad Request                │
│  3. Validate body (Bean Validation)             │
│  4. If invalid → 400 with field errors          │
│  5. Delegate to CreatePurchaseOrderUseCase      │
└─────────────────────────┬───────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────┐
│         CreatePurchaseOrderService              │
│  1. Check idempotencyKey in DynamoDB            │
│     → If found: return cached response (200)    │
│  2. Call ReserveTicketsUseCase                  │
└──────────────┬──────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────┐
│           ReserveTicketsService                 │
│  1. findById(eventId)                           │
│     → If not found: throw EventNotFoundException│
│  2. findAvailable(eventId, quantity)            │
│     → If insufficient: throw NotAvailableExc.  │
│  3. For each ticket:                            │
│     update(AVAILABLE → RESERVED, version+1)    │
│     ConditionExpression: version = expectedVer │
│     → If ConditionalCheckFailed: retry(409)    │
│  4. Save idempotencyKey → response in DynamoDB  │
│  5. Create Order(PENDING) in DynamoDB           │
│  6. Return OrderResponse(RESERVED, expiresAt)  │
└─────────────────────────┬───────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────┐
│        CreatePurchaseOrderService               │
│  6. Publish OrderCreatedEvent to SQS            │
│     (async — does not block response)           │
└─────────────────────────┬───────────────────────┘
                          │
                          ▼
       201 Created — OrderResponse
       { orderId, status: RESERVED, expiresAt }

═══════════════════════════════════════════════════
            ASYNC — SQS Consumer
═══════════════════════════════════════════════════

SqsOrderConsumer polls every 5s
         │
         ▼
ProcessOrderService
  1. findById(orderId)
  2. If status is CONFIRMED/CANCELLED → skip (idempotent)
  3. update(Order → PROCESSING)
  4. For each ticketId:
     confirmPending(ticket)   → PENDING_CONFIRMATION
     sell(ticket)             → SOLD
  5. update(Order → CONFIRMED)
  6. deleteMessage from SQS
```

## Error Paths

```
Missing X-Idempotency-Key:
  → 400 Bad Request: "X-Idempotency-Key header is required"

Event not found:
  → EventNotFoundException → GlobalErrorHandler → 404
  → { status: 404, message: "Event not found: evt_xxx" }

Not enough tickets:
  → TicketNotAvailableException → GlobalErrorHandler → 409
  → { status: 409, message: "No available tickets for event: evt_xxx" }

Concurrent modification (optimistic lock):
  → ConditionalCheckFailedException → retry logic
  → After max retries: TicketNotAvailableException → 409

DynamoDB circuit breaker open:
  → CallNotPermittedException → GlobalErrorHandler → 503
  → { status: 503, message: "Service temporarily unavailable" }

Duplicate idempotency key:
  → Return cached response immediately (no processing)
  → Same orderId, same status as original response
```

## Expiry Flow (Scheduler)

```
ExpiredReservationScheduler
  @Scheduled(fixedDelay = 60000)
  @SchedulerLock(name="release-expired", lockAtMostFor=55s)
         │
         ▼ (only ONE ECS instance runs this — ShedLock)
ReleaseExpiredReservationsService
  1. Scan tickets WHERE status=RESERVED AND expiresAt < now
  2. For each expired ticket:
     release(ticket) → AVAILABLE
  3. Log "Released N expired reservations"

Note: ShedLock ensures only one instance runs per cycle.
      lockAtMostFor=55s releases lock even on instance crash.
```
