# Trade-offs, Limitations and Improvements

## Decisions I Would Change in Production

### 1. `findExpiredReservations` — Full Table Scan
**Current situation**: The expiration scheduler performs a full `scan` on the `emp-tickets` table looking for entries with `status=RESERVED` and `expiresAt < now`.

**Problem in production**: With millions of tickets, a full scan is expensive ($) and slow.

**Real solution**: GSI on `(status, expiresAt)` or use DynamoDB Streams + Lambda that reacts to changes. A more elegant alternative: native DynamoDB TTL with a Lambda trigger that releases the ticket when it expires.
```
Production:
tickets with expiresAt → DynamoDB TTL → Stream → Lambda → release ticket
```

**Why not done here:**
For a technical assessment with controlled data volume, the scan approach demonstrates the concept correctly. GSI option would be the first production improvement.

---

### 2. SQS Standard vs FIFO
**Current situation**: SQS Standard (at-least-once, no guaranteed order).

**Problem**: The same orderId could be processed twice in parallel if there are fast retries.

**Current mitigation**: `ProcessOrderService` is idempotent — detects final state and skips.

**In production**: SQS FIFO with `MessageGroupId = orderId` guarantees at-most-once processing per order. More expensive but more predictable.

---

### 3. Ticket Reservation Without Distributed Lock
**Current situation**: Optimistic locking with conditional writes. If 100 users compete for the last ticket, 99 will receive 409 and have to retry.

**Problem**: High contention on popular events generates many 409s and retries that saturate the API.

**Real solution for high demand**:
- **Range pre-allocation**: each app instance receives a range of ticketIds to assign — no contention.
- **Redis + Lua scripts**: atomic reservation with TTL in memory, async flush to DynamoDB.
- **Virtual waiting room**: virtual queue for peaks like Coldplay ticket sales.

---

### 4. SQS Polling vs Event-driven
**Current situation**: `@Scheduled` poll every 5 seconds.

**Problem**: Up to 5s latency + polling costs when the queue is empty.

**In production**: SQS Long Polling (waitTimeSeconds=20) is already configured, but in ECS the ideal approach is to use **Spring Cloud AWS SQS Listener** or directly **AWS Lambda** as the consumer — reacts in milliseconds and scales to zero when there are no messages.

---

### 5. Single-region vs Multi-region
**Current situation**: Designed for us-east-1.

**For a national Colombian ticketing system**: DynamoDB Global Tables with replication to sa-east-1 (São Paulo, the closest region) + Route 53 latency-based routing.

**Production improvement:**
Implement fallback responses at the handler level:

## Known Limitations of This Implementation

| Limitation | Impact | Solution |
|---|---|---|
| No JWT authentication | Anyone can create orders | Spring Security WebFlux + Cognito |
| CORS not configured | Not suitable for direct browser use | WebFluxConfigurer + CORS policy |
| Logs without global correlation ID | Hard to trace a transaction | MDC with `traceId` propagated via WebFilter |
| No active circuit breaker on DynamoDB | DynamoDB failure cascades to the entire app | Resilience4j CircuitBreaker on repos |
| Non-distributed scheduler | With 3 instances, 3 schedulers run in parallel | ShedLock or EventBridge Scheduler |
| No pagination on `GET /events` | With 10k events, the response is huge | Cursor-based pagination with `lastEvaluatedKey` |
| Audit table not actually used | Created but not written to | Implement AuditService that persists changes |

---

## Improvements for a Real Production Environment

### Observability
```yaml
# Distributed tracing with AWS X-Ray
management:
  tracing:
    sampling:
      probability: 1.0
```
- **CloudWatch Structured Logs** — JSON with `traceId`, `orderId`, `userId`, `duration`
- **CloudWatch Dashboard** — business metrics: tickets/min sold, failed orders, P95/P99 latency
- **Alarms**: error rate >1%, P99 latency >500ms, DLQ with messages

### Performance
- **DynamoDB DAX** (in-memory cache) for `GET /events/{eventId}` — reduce latency from 10ms to <1ms
- **ElastiCache Redis** for sessions and distributed rate limiting
- **CDN CloudFront** in front of the ALB for cacheable responses (event availability)

### Resilience
```java
// Circuit breaker on DynamoDB repository
@CircuitBreaker(name = "dynamodb", fallbackMethod = "fallbackFindById")
public Mono<Event> findById(EventId eventId) { ... }
```

### Cost Optimization
- **DynamoDB on-demand** in production (PAY_PER_REQUEST) — no provisioning waste
- **ECS Fargate Spot** for the SQS consumer — up to 70% savings, tolerant to interruptions
- **Reserved Capacity DynamoDB** for predictable base load — 20-40% savings
- **SQS Long Polling** — reduces empty calls from 8640/day to ~432/day per instance

---

## Reflection: What Would I Do Differently from Day 1?

1. **Event Sourcing** for the ticket domain — instead of mutable state, every change is an immutable event. Free audit trail, state replay, easy debugging.

2. **Explicit CQRS** — separate the write model (commands) from the read model (queries). `GET /availability` could be served from a denormalized projection in ElastiCache, without touching DynamoDB.

3. **Contract testing with Pact** — consumers (frontend, mobile) publish contracts; the API validates them in CI. Prevents silent breaking changes.

4. **Canary deployments** with CodeDeploy — in a ticketing system, a production bug during a popular sale is catastrophic. Canary at 5% with automatic rollback if the error rate rises.

5. **DynamoDB Streams + Lambda for domain events** — when a ticket is sold, a Lambda can update counters in real time, notify the user via SNS, and update the dashboard without coupling that logic to the main service.

---

## Java 25 — Version Decision

**Context:** The technical test explicitly requires Java 25.

**Reality:** Java 25 is the current reference version. Temurin 25 (`eclipse-temurin:25-jdk-alpine`) is already available as a stable Docker image. The features used in this project are all GA (not preview):

| Feature | Status in Java 25 | Usage in this project |
|---|---|---|
| Virtual Threads (Project Loom) | GA since Java 21 | `Executors.newVirtualThreadPerTaskExecutor()` in concurrency tests, `spring.threads.virtual.enabled: true` |
| Pattern Matching in switch | GA since Java 21 | `GlobalErrorHandler.resolveStatus()` |
| Sealed interfaces | GA since Java 17 | `DomainEvent` sealed interface with records |
| Records with compact constructors | GA since Java 16 | `Money`, `EventId`, `TicketId`, all domain VOs |

**Trade-off:** AWS SDK v2 is pinned at `2.20.26` for compatibility with DynamoDB Local. Higher SDK versions have an AWS4 signature incompatibility with DynamoDB Local in development. In production (real AWS) the latest SDK version could be used without restrictions.

---

## `concatMap` vs `flatMap` in `ReserveTicketsService`

This is the most important design decision in the reservation service.

### The Problem

When reserving N tickets for an order, we need to write N records to DynamoDB with conditional writes (optimistic locking). If ticket K fails (because another user took it first), tickets 1..K-1 are already in `RESERVED` state — orphaned, without a valid order.

### The Options

**Option A: `flatMap` (concurrent)**
```java
// All N writes happen in parallel
Flux.fromIterable(tickets)
    .flatMap(ticket -> ticketRepository.update(ticket.reserve(...)))
    .collectList()
```

Advantage: faster (N simultaneous writes).
Critical problem: if the write for ticket 3 fails while tickets 1, 2, 4 already completed, **there is no reliable way to know which ones to compensate**. The completion order with flatMap is non-deterministic.

**Option B: `concatMap` (sequential) ← adopted decision**
```java
List<Ticket> reservedSoFar = new ArrayList<>();

Flux.fromIterable(tickets)
    .concatMap(ticket -> ticketRepository.update(ticket.reserve(...))
            .doOnSuccess(reservedSoFar::add))  // accumulates exactly those that succeeded
    .collectList()
    .onErrorResume(ex -> compensate(reservedSoFar)) // releases exactly those that failed
```

Advantage: exact and reliable compensation.
Cost: slightly higher latency for multi-ticket orders (max 10 tickets in sequence).

### Why It's Worth It

- **Most common case (80%+ of orders):** 1 ticket reservation. With `concatMap`, the latency difference vs `flatMap` is **zero** — it's a single operation.
- **Multi-ticket cases (2-10 tickets):** additional latency is on the order of N×5ms (DynamoDB Local round-trip). In production with real DynamoDB it is comparable.
- **Correctness in financial systems:** an orphaned ticket locked indefinitely is lost money for the user. Clean compensation is worth the latency trade-off.
- **Safety net:** even if compensation partially fails (network error), the expiration scheduler releases tickets within the configured TTL (10 minutes in production).

**Decision: correctness > speed in a payments system.**
