# Trade-offs, Limitations and Improvements — v2 Microservices

## What Changed from v1 (Problems Solved)

| v1 Problem | v2 Solution | Why |
|---|---|---|
| `findExpiredReservations` full table scan O(table) | GSI query `STATUS#ACTIVE AND expiresAt <= now` — O(results) | 62,500 RCU per scan at 1M reservations is unacceptable |
| `@Scheduled + subscribe()` fire-and-forget SQS poll | `@SqsListener(ON_SUCCESS)` — Spring Cloud AWS 3.x | Overlapping polling cycles under load, no backpressure |
| Dual write `save(order)` → `publish(SQS)` | `TransactWriteItems([order, outbox])` | Zombie PENDING orders if SQS fails between calls |
| Item per ticket (50K PutItem per event) | Atomic counter `availableCount` on event item | O(N) writes vs O(1), doesn't scale |
| AuditService implemented but never called | Integrated in reservation-service + consumer-service | Silent state transitions, no compliance trail |
| Dockerfile Java 21, pom.xml Java 25 | All Dockerfiles use `eclipse-temurin:25` | Build fails if base image doesn't match compiler |

---

## Current Trade-offs (Known and Accepted)

### 1. SQS Standard vs FIFO

**Decision:** SQS Standard (at-least-once, no guaranteed order).

**Risk:** Same `orderId` processed twice in rapid succession.

**Mitigation:** `ProcessOrderService` is idempotent — if `order.status() == CONFIRMED`, returns `Mono.empty()` immediately.

**Production upgrade:** FIFO queue with `MessageGroupId = reservationId` guarantees at-most-once per order. Trade-off: 300 msg/s throughput limit vs Standard's unlimited. Acceptable for most events; not for Coldplay-scale.

---

### 2. Reservation Expiry — LocalStack vs Production

**Decision:** Dual-mode expiry:
- **Production:** DynamoDB TTL auto-deletes → DynamoDB Streams → Lambda compensates `availableCount`
- **Local dev (LocalStack):** Reconciliation scheduler queries GSI1 every 60s

**Why LocalStack fallback:** LocalStack's DynamoDB Streams support is limited (event delivery unreliable in v3.x). The scheduler is a documented workaround, not a production approach.

**Why not scheduler in production:** O(results) GSI query is fine, but DynamoDB Streams + Lambda is O(1) — reacts in seconds to each individual expiry, no polling cost, fully serverless.

---

### 3. Conditional Writes vs Distributed Lock (Redis)

**Decision:** DynamoDB conditional writes (`availableCount >= n AND version = :expected`).

**Why not Redis:**
- No additional infrastructure (no Redis cluster, no SPOF)
- DynamoDB handles atomicity natively at item level
- No lock release needed on crash — self-healing
- Works across any number of ECS instances without coordination

**Limitation:** Under extreme contention (1,000 concurrent requests for 1 ticket), most requests will fail the conditional check and retry. After 3 retries: 409 Conflict.

**Production upgrade for popular events:** Write sharding (8 shards × smaller counters) + virtual waiting room. Documented in ARCHITECTURE.md.

---

### 4. Outbox Poller vs DynamoDB Streams for SQS Publish

**Decision:** OutboxPoller in consumer-service polls `emp-outbox` every 5s.

**Latency impact:** Up to 5 seconds between order creation and SQS delivery.

**Why acceptable:** The reservation is already confirmed (user has the seat). The order status change from PENDING to CONFIRMED is async by design — the UI polls `/orders/{id}` or uses WebSocket.

**Production upgrade:** DynamoDB Streams on `emp-outbox` → Lambda → SQS. Reduces latency to <500ms and eliminates the polling compute cost.

---

### 5. Idempotency Window: 24 hours

**Decision:** `X-Idempotency-Key` TTL = 24 hours.

**Trade-off:** If a client reuses the same key after 24h (different intent), the request will be processed as new. This is the industry-standard window (Stripe, PayPal).

**Why not longer:** Storage cost grows with window size. 24h covers all reasonable retry scenarios (network timeouts, app restarts).

---

### 6. Per-Service DynamoDB Repositories (Not Shared)

**Decision:** Each service has its own repository implementations. `DynamoDbReservationRepository` exists in both reservation-service and consumer-service.

**Why:** Service independence — consumer-service must be deployable without coupling to reservation-service's classpath. In a distributed system, shared libraries create deployment coupling.

**Trade-off:** Some code duplication in entity classes and repository implementations.

**Alternative if this bothers you:** Shared `persistence` module (separate from `shared/domain`). Added complexity vs current 2-service duplication. Not worth it at this scale.

---

## Known Limitations

| Limitation | Impact | Roadmap Solution |
|---|---|---|
| No authentication | Any userId can create orders | Spring Security WebFlux + JWT + Cognito |
| No rate limiting | API vulnerable to abuse | API Gateway throttling or WebFlux rate limiter filter |
| No pagination on `GET /events` | Large response with many events | Cursor-based pagination with DynamoDB `lastEvaluatedKey` |
| DynamoDB Streams not in LocalStack | Expiry compensation uses scheduler fallback | Documented — production uses real Streams |
| Write sharding wired but not default | Default single counter, sharding documented | Enable via feature flag for high-demand events |
| No distributed tracing | Hard to trace cross-service request | AWS X-Ray + MDC propagation via HTTP headers |
| Terraform uses rolling deploy | Brief mixed-version state during deploy | Blue-green with CodeDeploy + target groups |

---

## Future Improvements (Backlog)

### High Priority
1. **JWT authentication** — Spring Security + Cognito User Pool + WebFlux security filter
2. **Rate limiting** — 100 req/min/IP on purchase endpoints, AWS API Gateway or WebFlux filter
3. **DynamoDB Streams + Lambda** — replace scheduler fallback for expiry compensation
4. **AWS X-Ray distributed tracing** — propagate trace headers between microservices

### Medium Priority
5. **Virtual waiting room** — SQS queue for peak demand events (Coldplay-style)
6. **CQRS read model** — ElastiCache for `GET /availability` — <1ms vs 10ms DynamoDB
7. **Contract testing with Pact** — prevent silent breaking changes between services
8. **Canary deployments** — CodeDeploy with automatic rollback on error rate threshold

### Low Priority
9. **Event Sourcing for tickets** — immutable event log, replay, free audit trail
10. **DynamoDB DAX** — in-memory cache for hot event availability queries
11. **Multi-region** — DynamoDB Global Tables to sa-east-1 (São Paulo) for Colombian latency

---

## Java 25 Decision

| Feature | Status | Usage |
|---|---|---|
| Records | GA since Java 16 | All domain models, DTOs, domain events |
| Sealed interfaces | GA since Java 17 | `DomainEvent` with exhaustive switch |
| Pattern Matching switch | GA since Java 21 | `GlobalErrorHandler`, state routing |
| Virtual Threads | GA since Java 21 | `spring.threads.virtual.enabled: true` |

AWS SDK pinned at `2.20.26` for DynamoDB Local compatibility. Production uses latest SDK without restrictions.
