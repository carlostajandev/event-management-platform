# Architecture Documentation

## 1. High-Level AWS Architecture

```
                        ┌─────────────────────────────────────────────┐
                        │              AWS Cloud (us-east-1)           │
                        │                                              │
                        │  ┌──────────────────────────────────────┐   │
                        │  │           Route 53 (DNS)             │   │
                        │  │     api.ticketing.nequi.com          │   │
                        │  └──────────────────┬───────────────────┘   │
                        │                     │                        │
                        │  ┌──────────────────▼───────────────────┐   │
                        │  │     CloudFront + WAF                 │   │
                        │  │  · Rate limiting per IP              │   │
                        │  │  · OWASP managed rules               │   │
                        │  │  · DDoS protection (Shield)          │   │
                        │  └──────────────────┬───────────────────┘   │
                        │                     │                        │
                        │  ┌──────────────────▼───────────────────┐   │
                        │  │  Application Load Balancer (ALB)     │   │
                        │  │  · TLS termination (ACM)             │   │
                        │  │  · HTTP → HTTPS redirect             │   │
                        │  │  · Health check /actuator/health     │   │
                        │  └────┬──────────────┬──────────────┬───┘   │
                        │       │              │              │        │
                        │  ┌────▼───┐    ┌─────▼──┐    ┌─────▼──┐   │
                        │  │Fargate │    │Fargate │    │Fargate │   │
                        │  │AZ-1a   │    │AZ-1b   │    │AZ-1c   │   │
                        │  │Java 21 │    │Java 21 │    │Java 21 │   │
                        │  └────┬───┘    └─────┬──┘    └─────┬──┘   │
                        │       └──────────────┼──────────────┘        │
                        │                      │                        │
                        │         ┌────────────┴────────────┐          │
                        │         │                         │          │
                        │  ┌──────▼──────┐        ┌─────────▼──────┐  │
                        │  │  DynamoDB   │        │      SQS       │  │
                        │  │  6 tables   │        │  purchase-     │  │
                        │  │  PITR on    │        │  orders        │  │
                        │  │  Encrypted  │        │  + DLQ         │  │
                        │  └─────────────┘        └────────────────┘  │
                        │                                              │
                        │  ┌────────────────────────────────────────┐ │
                        │  │  Secrets Manager · CloudWatch · X-Ray  │ │
                        │  └────────────────────────────────────────┘ │
                        └─────────────────────────────────────────────┘
```

---

## 2. Clean Architecture — Layers and Dependencies

```
┌──────────────────────────────────────────────────────────────────────┐
│                         INFRASTRUCTURE                                │
│                                                                       │
│  ┌────────────────┐  ┌──────────────────┐  ┌──────────────────────┐ │
│  │   Web Layer    │  │   Persistence    │  │      Messaging       │ │
│  │  EventHandler  │  │  EventDynamo     │  │  SqsMessagePublisher │ │
│  │  OrderHandler  │  │  TicketDynamo    │  │  SqsOrderConsumer    │ │
│  │  EventRouter   │  │  OrderDynamo     │  │  ExpiredScheduler    │ │
│  │  OrderRouter   │  │  IdempotencyRepo │  │                      │ │
│  │  CorrelIdFilter│  │  AuditRepo       │  │                      │ │
│  │  GlobalError   │  │  Mappers         │  │                      │ │
│  └───────┬────────┘  └────────┬─────────┘  └──────────┬───────────┘ │
└──────────┼───────────────────┼────────────────────────┼─────────────┘
           │ drives            │ implements             │ implements
           ▼                   ▼                        ▼
┌──────────────────────────────────────────────────────────────────────┐
│                          APPLICATION                                  │
│                                                                       │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │                        Use Cases                                │ │
│  │  CreateEventService        GetEventService                      │ │
│  │  ReserveTicketsService     ProcessOrderService                  │ │
│  │  CreatePurchaseOrderSvc    ReleaseExpiredResSvc                 │ │
│  │  QueryOrderStatusService   AuditService                         │ │
│  └─────────────────────────────────────────────────────────────────┘ │
│                                                                       │
│  port/in (interfaces)        port/out (interfaces)                    │
│  ────────────────────        ───────────────────                      │
│  CreateEventUseCase          EventRepository                          │
│  ReserveTicketsUseCase       TicketRepository                         │
│  CreatePurchaseOrderUseCase  OrderRepository                          │
│  QueryOrderStatusUseCase     IdempotencyRepository                    │
│  GetEventUseCase             MessagePublisher                         │
│  GetAvailabilityUseCase      AuditRepository                          │
└─────────────────────────────────────────────────────────────────────-┘
           │ depends only on domain interfaces
           ▼
┌──────────────────────────────────────────────────────────────────────┐
│                            DOMAIN                                     │
│               ★ ZERO external dependencies ★                         │
│                                                                       │
│  Models          Value Objects      Services        Exceptions        │
│  ──────          ────────────       ────────        ──────────        │
│  Event           EventId            TicketState     EventNotFound     │
│  Ticket          TicketId           Machine         TicketNotAvail.   │
│  Order           OrderId                            InvalidTicketSt.  │
│  TicketStatus    Money                              OrderNotFound     │
│  OrderStatus     Venue                                                │
│                  IdempotencyKey                                        │
└──────────────────────────────────────────────────────────────────────┘

RULE: Dependency arrows point INWARD only.
      Domain imports NOTHING from Spring, AWS SDK, or any framework.
```

---

## 3. Ticket Reservation — Sequence Diagram

```
Client       EventHandler    IdempotencyRepo  EventRepo   TicketRepo   SQS
  │               │                │              │            │         │
  │─POST /orders─►│                │              │            │         │
  │  X-Idem-Key   │                │              │            │         │
  │               │─exists(key)?──►│              │            │         │
  │               │◄─false─────────│              │            │         │
  │               │                │              │            │         │
  │               │─findById(evt)──────────────►  │            │         │
  │               │◄─Event─────────────────────   │            │         │
  │               │                │              │            │         │
  │               │─findAvailable(N qty)──────────────────────►│         │
  │               │◄─[tkt_1, tkt_2]───────────────────────────│         │
  │               │                │              │            │         │
  │               │─update(tkt_1, RESERVED, version=N+1)──────►│         │
  │               │  ConditionExpression: version=N            │         │
  │               │◄─OK (version N+1 written)─────────────────│         │
  │               │                │              │            │         │
  │               │─save(key, cachedResponse)─────►│            │         │
  │               │◄─OK────────────│              │            │         │
  │               │                │              │            │         │
  │◄─201 RESERVED─│                │              │            │         │
  │  orderId       │                │              │            │         │
  │  expiresAt     │                │              │            │         │
  │               │                │              │            │         │
  │               │─publish(OrderCreatedEvent)──────────────────────────►│
  │               │  (async, fire-and-forget)                             │
  │               │                │              │            │         │
  │               │           SqsOrderConsumer polls every 5s            │
  │               │           ──────────────────────────────────         │
  │               │           RESERVED → PENDING_CONFIRMATION → SOLD     │
  │               │           Order: PENDING → PROCESSING → CONFIRMED    │
```

---

## 4. Async Order Processing — Sequence Diagram

```
SqsConsumer    OrderRepo    TicketRepo    SQS
    │               │            │          │
    │─poll(max=10)──────────────────────────►│
    │◄─[OrderCreatedEvent]──────────────────│
    │               │            │          │
    │─findById(orderId)─►│        │          │
    │◄─Order(PENDING)────│        │          │
    │               │            │          │
    │  [idempotency check: if CONFIRMED/CANCELLED → skip]
    │               │            │          │
    │─update(PROCESSING)─►│       │          │
    │◄─OK─────────────────│       │          │
    │               │            │          │
    │─findById(tkt_1)────────────►│          │
    │◄─Ticket(RESERVED)──────────│          │
    │─confirmPending(tkt_1)──────►│          │
    │◄─Ticket(PENDING_CONF)──────│          │
    │─sell(tkt_1)────────────────►│          │
    │◄─Ticket(SOLD)──────────────│          │
    │               │            │          │
    │─update(CONFIRMED)──►│       │          │
    │◄─OK─────────────────│       │          │
    │               │            │          │
    │─deleteMessage()───────────────────────►│
    │◄─OK───────────────────────────────────│
    │               │            │          │
    │  [on error: do NOT delete → SQS retries up to 3x → DLQ]
```

---

## 5. Ticket State Machine

```
                              reserve(userId, orderId, TTL)
         ┌─────────────────────────────────────────────────────────┐
         │                                                         │
   ┌─────▼──────┐                                         ┌───────▼───────┐
   │ AVAILABLE  │────────complimentary()──────────────────►COMPLIMENTARY  │
   └─────┬──────┘                                         │   (FINAL)     │
         │                                                └───────────────┘
         │ reserve(userId, orderId, expiresAt)
         ▼
   ┌─────────────┐
   │  RESERVED   │─────── expiresAt < now ──────────────────────────┐
   └─────┬───────┘        (ExpiredReservationScheduler)             │
         │                                                           │
         │ confirmPending()                                          │
         ▼                                                           ▼
  ┌──────────────────────┐                                  ┌───────────────┐
  │  PENDING_CONFIRMATION│                                  │   AVAILABLE   │
  └──────────┬───────────┘                                  │  (released)   │
             │                                              └───────────────┘
     ┌────────┴────────┐                                           ▲
     │                 │                                           │
     │ sell()          │ release()                                 │
     ▼                 ▼                                           │
  ┌──────┐      ┌───────────┐                                      │
  │ SOLD │      │ AVAILABLE │──────────────────────────────────────┘
  │(FINAL│      │(payment   │  (same AVAILABLE state, ticket back in pool)
  └──────┘      │ failed)   │
                └───────────┘

Invalid transitions throw InvalidTicketStateException (mapped to 409).
```

---

## 6. DynamoDB Data Model

```
┌──────────────────────────────────────────────────────────────────────┐
│  Table: emp-events                                                    │
│  ─────────────────                                                    │
│  PK: eventId (String)                                                 │
│  Attributes: name, description, eventDate, venueName, venueCity,     │
│              venueCountry, totalCapacity, ticketPrice, currency,      │
│              status, version, createdAt, updatedAt                    │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│  Table: emp-tickets                                                   │
│  ──────────────────                                                   │
│  PK: ticketId (String)                                                │
│  Attributes: eventId, status, userId, orderId, price, currency,      │
│              reservedAt, expiresAt, confirmedAt, version              │
│                                                                       │
│  GSI: eventId-status-index                                            │
│  ────────────────────────                                             │
│  PK: eventId   SK: status                                             │
│  Purpose: Query "all AVAILABLE tickets for event X" without full scan │
│  Used by: ReserveTicketsService, GetAvailabilityService               │
│  Projection: ALL                                                      │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│  Table: emp-orders                                                    │
│  ─────────────────                                                    │
│  PK: orderId (String)                                                 │
│  Attributes: userId, eventId, ticketIds[], quantity, totalAmount,     │
│              currency, status, failureReason, version                 │
│                                                                       │
│  GSI: userId-index                                                    │
│  ─────────────────                                                    │
│  PK: userId                                                           │
│  Purpose: Query all orders for a user                                 │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│  Table: emp-idempotency                                               │
│  ──────────────────────                                               │
│  PK: idempotencyKey (String)                                          │
│  Attributes: responseJson, createdAt                                  │
│                                                                       │
│  TTL: expiresAt (Unix epoch)                                          │
│  ────────────────────────────                                         │
│  DynamoDB deletes expired keys automatically — no cleanup Lambda.     │
│  Keys expire after 24h (configurable via IDEMPOTENCY_TTL_HOURS).      │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│  Table: emp-audit                                                     │
│  ────────────────                                                     │
│  PK: entityId (String)   SK: timestamp (String ISO-8601)              │
│  Attributes: entityType, action, userId, correlationId,               │
│              previousStatus, newStatus, metadata                      │
│                                                                       │
│  Pattern: All state changes for entity X in chronological order.      │
│  Example: tkt_001 → [AVAILABLE, RESERVED, SOLD]                       │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│  Table: emp-shedlock                                                  │
│  ────────────────────                                                 │
│  PK: _id (String)                                                     │
│  Attributes: lockUntil, lockedAt, lockedBy                            │
│                                                                       │
│  Purpose: Distributed scheduler lock across ECS instances.            │
│  Only ONE instance runs the expiry job per cycle.                     │
│  lockAtMostFor=55s — lock released even on instance crash.            │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 7. Concurrency Control — Optimistic Locking

```
Request A                  DynamoDB              Request B
    │                          │                     │
    │─GET ticket (v=1)────────►│                     │
    │◄─Ticket(v=1, AVAILABLE)──│                     │
    │                          │─GET ticket (v=1)────►│ (concurrent)
    │                          │◄─Ticket(v=1,AVAIL.)──│
    │                          │                     │
    │─UPDATE RESERVED          │                     │
    │  WHERE version=1─────────►│                     │
    │◄─OK (v=2 written)────────│                     │
    │                          │                     │
    │                          │ UPDATE RESERVED      │
    │                          │  WHERE version=1─────►│ (stale version!)
    │                          │◄─ConditionalCheck     │
    │                          │   FailedException─────│
    │                          │                     │
    │                          │          409 TicketNotAvailableException
    │                          │                     │
    │                          │  (no overselling — one winner, one loser)
```

---

## 8. VPC Network Architecture

```
VPC: 10.0.0.0/16
│
├── PUBLIC SUBNETS (internet-facing)
│   ├── 10.0.1.0/24  (AZ-1a)  ┐
│   ├── 10.0.2.0/24  (AZ-1b)  ├── ALB only
│   └── 10.0.3.0/24  (AZ-1c)  ┘
│       └── Internet Gateway ──► internet
│
├── PRIVATE SUBNETS (no direct internet access)
│   ├── 10.0.10.0/24 (AZ-1a)  ┐
│   ├── 10.0.20.0/24 (AZ-1b)  ├── ECS Fargate Tasks
│   └── 10.0.30.0/24 (AZ-1c)  ┘
│       ├── NAT Gateway ──► internet (ECR pull, OS updates)
│       └── VPC Endpoints ──► AWS services (no NAT cost)
│
└── VPC ENDPOINTS (traffic stays within AWS)
    ├── dynamodb   (Gateway)   → DynamoDB
    ├── sqs        (Interface) → SQS
    ├── ecr.api    (Interface) → ECR image metadata
    ├── ecr.dkr    (Interface) → ECR image layers
    └── logs       (Interface) → CloudWatch Logs

SECURITY GROUPS:
  ALB:           inbound 443+80 from 0.0.0.0/0
                 outbound all
  ECS Tasks:     inbound 8080 from ALB SG only
                 outbound all (VPC endpoints + NAT)
  VPC Endpoints: inbound 443 from ECS Tasks SG only
```

---

## 9. CI/CD Pipeline Flow

```
Developer
    │
    │ git push feature/TICK-XXX
    ▼
┌──────────────────────────────────────────────────────────────────┐
│                     Pull Request                                 │
│                                                                  │
│  ┌─────────────────────┐   ┌──────────────────────────────────┐ │
│  │   Build & Test      │   │    Terraform Validate            │ │
│  │  ./mvnw verify      │   │  terraform fmt -check -recursive │ │
│  │  65 tests           │   │  terraform init -backend=false   │ │
│  │  JaCoCo gate        │   │  terraform validate              │ │
│  └─────────────────────┘   └──────────────────────────────────┘ │
│  (no AWS credentials needed for either job)                      │
└──────────────────────────────────────────────────────────────────┘
    │
    │ merge to main
    ▼
┌──────────────────────────────────────────────────────────────────┐
│                     Push to main                                 │
│                                                                  │
│  ┌──────────────────┐  ┌─────────────────┐  ┌────────────────┐  │
│  │ Build & Test     │  │ Docker Build    │  │ Terraform Plan │  │
│  │ (same as above)  │  │ JAR → image     │  │ against staging│  │
│  └──────────────────┘  │ push to ECR     │  └────────────────┘  │
│                         └─────────────────┘                      │
└──────────────────────────────────────────────────────────────────┘
    │
    │ git tag v1.0.0
    ▼
┌──────────────────────────────────────────────────────────────────┐
│               Production Deploy (tag v*)                         │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  ⚠ Requires manual approval in GitHub Environments       │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  terraform apply -var-file=environments/prod.tfvars              │
│       ↓                                                          │
│  ECS rolling update (min 50% healthy)                            │
│       ↓                                                          │
│  aws ecs wait services-stable                                    │
│       ↓                                                          │
│  Smoke test: GET /actuator/health → {"status":"UP"}              │
└──────────────────────────────────────────────────────────────────┘
```

---

## 10. Observability — Three Pillars

```
┌──────────────────────────────────────────────────────────────────────┐
│                          LOGS (CloudWatch)                            │
│                                                                       │
│  Every log line contains:                                             │
│  {                                                                    │
│    "timestamp": "2027-06-01T10:00:00Z",                              │
│    "level": "INFO",                                                   │
│    "correlationId": "550e8400-e29b-41d4-a716-446655440000",  ◄── MDC  │
│    "service": "event-management-platform",                            │
│    "orderId": "ord_x1y2z3w4",                                        │
│    "eventId": "evt_a1b2c3d4",                                        │
│    "userId": "usr_001",                                               │
│    "message": "Tickets reserved"                                      │
│  }                                                                    │
│                                                                       │
│  CorrelationIdFilter → MDC → all downstream logs inherit the ID       │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│                    METRICS (Prometheus / CloudWatch)                  │
│                                                                       │
│  Endpoint: GET /actuator/prometheus                                   │
│                                                                       │
│  Key metrics:                                                         │
│  · http_server_requests_seconds  — latency P95/P99 per endpoint       │
│  · jvm_memory_used_bytes         — heap usage                         │
│  · resilience4j_circuitbreaker   — circuit breaker state              │
│  · process_cpu_usage             — CPU utilization                    │
│                                                                       │
│  CloudWatch Alarms:                                                   │
│  · SQS DLQ messages > 0         → immediate alert                    │
│  · P99 latency > 500ms          → warning                            │
│  · Error rate > 1%              → critical                            │
│  · ECS CPU > 80%                → scale-out trigger                  │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│                      TRACES (AWS X-Ray)                               │
│                                                                       │
│  X-Correlation-Id propagation:                                        │
│                                                                       │
│  Client ──► ALB ──► ECS Task ──► DynamoDB call                        │
│              │          │              │                              │
│         trace-id    trace-id      trace-id (same)                    │
│                                                                       │
│  · AWS SDK v2 auto-instruments DynamoDB calls                         │
│  · SQS message attributes carry X-Amzn-Trace-Id                      │
│  · Full request trace visible in X-Ray console                        │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 11. Component Interaction Diagram

```
                    ┌──────────────────────────────────┐
                    │         HTTP Request              │
                    └────────────────┬─────────────────┘
                                     │
                    ┌────────────────▼─────────────────┐
                    │       CorrelationIdFilter         │
                    │  Sets X-Correlation-Id in MDC     │
                    └────────────────┬─────────────────┘
                                     │
                    ┌────────────────▼─────────────────┐
                    │     EventRouter / OrderRouter     │
                    │   (Functional routing — no @MVC)  │
                    └────────────────┬─────────────────┘
                                     │
                    ┌────────────────▼─────────────────┐
                    │    EventHandler / OrderHandler    │
                    │  1. Extract input from request    │
                    │  2. Validate (Bean Validation)    │
                    │  3. Delegate to use case          │
                    │  4. Build HTTP response           │
                    └────────────────┬─────────────────┘
                                     │ port/in interface
                    ┌────────────────▼─────────────────┐
                    │         Use Case (Service)        │
                    │  · Orchestrates domain logic      │
                    │  · Calls repository ports         │
                    │  · Never knows about HTTP/JSON    │
                    └────┬─────────────────────────┬───┘
                         │ port/out interface       │ port/out
                    ┌────▼──────────┐         ┌────▼──────────┐
                    │  DynamoDB     │         │     SQS       │
                    │  Repository   │         │   Publisher   │
                    │  @CircuitBkr  │         │  @CircuitBkr  │
                    │  @Retry       │         │  @Retry       │
                    └───────────────┘         └───────────────┘
                         │                         │
                    ┌────▼──────────┐         ┌────▼──────────┐
                    │   DynamoDB    │         │     SQS       │
                    │   (AWS)       │         │    (AWS)      │
                    └───────────────┘         └───────────────┘
                                     │
                    ┌────────────────▼─────────────────┐
                    │        GlobalErrorHandler         │
                    │  Maps domain exceptions to HTTP   │
                    │  EventNotFound     → 404          │
                    │  TicketNotAvail.   → 409          │
                    │  ValidationExc.    → 400          │
                    │  Unhandled         → 500          │
                    └──────────────────────────────────┘
```