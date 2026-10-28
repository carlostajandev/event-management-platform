# ADR 0001 — v3 scope: from microservices to a modular monolith

- **Status:** Accepted
- **Date:** 2026-10-28
- **Baseline:** tag `v2-final` (commit `cdcdfe8`)

## Context

v2 split the platform into four Spring Boot services (`event`, `reservation`, `order`, `consumer`).
A review of v2 against the assessment spec found two kinds of problems:

1. **Too much structure where it adds nothing.** The four services share the same DynamoDB tables
   (`reservation-service` and `event-service` both own a `DynamoDbEventRepository` that writes to
   `emp-events`; three services write reservations). That is a *distributed monolith*: the operational
   cost of microservices without independent data ownership.
2. **Too little rigor where it matters.** Several race conditions allow overselling or leave state
   half-written, which is exactly what the spec asks to prevent.

## v2 findings (verified against the code)

| # | Where | Problem | Impact |
|---|---|---|---|
| 1 | `consumer-service/.../DynamoDbReservationRepository.updateStatus` | `UpdateItem` without `ConditionExpression`; reads first, writes later | An expired reservation whose tickets were already released can still be confirmed → **oversell** |
| 2 | `ReserveTicketsService` | `event.version()` captured **outside** `Mono.defer`; `retryWhen` reuses the stale version | Retries can never succeed; backoff only adds latency before the 409 |
| 3 | `ReserveTicketsService` | Inventory decrement, reservation save and audit are separate writes | A failed `save` leaks inventory (tickets decremented, no reservation) |
| 4 | `CreateOrderService` + `DynamoDbIdempotencyRepository` | Check-then-act: `findByKey`, later `putItem` without `attribute_not_exists` | Two concurrent requests with the same key create **two orders** |
| 5 | `ProcessOrderService` | Order, reservation and two audit records written independently | Not atomic: a failure leaves `CONFIRMED` order with `ACTIVE` reservation |
| 6 | `shared/domain` enums | Spec states `SOLD` and `COMPLIMENTARY` do not exist; other enums are used | RF4 not met as written |
| 7 | `DynamoDbEventRepository` (two copies) | Condition `availableCount >= :n AND version = :expected` | The version serializes every purchase on an event; races become needless 409s |
| 8 | All services | Shared tables across deployables | Distributed monolith |
| 9 | 20 call sites | `MDC` in reactive code | Thread-local context is lost or mixed across requests on thread hops |
| 10 | All `application.yml` | `spring.threads.virtual.enabled` on WebFlux apps | Little value on a Netty event loop; not justified |
| 11 | Use cases | Generic `IllegalStateException` / `IllegalArgumentException` | No explicit 409/422/403 mapping |
| 12 | 37 references | `@CircuitBreaker` around DynamoDB | See ADR 0004 |
| 13 | `pom.xml` | Spring Boot `4.0.3` | Later 4.0.x releases contain security fixes |
| 14 | Inventory item | Hot partition not discussed | Single counter item caps throughput per event |

## Requirements → v2 → v3

| Requirement | v2 | v3 |
|---|---|---|
| RF1 Event management | Create/list/get; counter on event item | Same; inventory item with one counter per state |
| RF2 Temporary hold ≤ 10 min | `ACTIVE` reservation with TTL; separate writes | Hold is created in the same transaction as the order; single `expiresAt = createdAt + 10 min` covers `RESERVED` **and** `PENDING_CONFIRMATION` |
| RF3 Enqueue immediately, return order id, consumer validates availability | Outbox + SQS; consumer confirms without conditions | Outbox item in the create transaction; consumer validates the hold and transitions conditionally (ADR 0002) |
| RF4 Order status with the five spec states | Different enums | `AVAILABLE, RESERVED, PENDING_CONFIRMATION, SOLD, COMPLIMENTARY`, unchanged names |
| RF5 Concurrency, no oversell | Conditional write + broken retry + non-atomic writes | One `TransactWriteItems` per transition, condition on the invariant, retry only on `TransactionConflict` |
| RF6 Periodic release of expired holds | `@Scheduled` + GSI query | Same GSI approach, conditional on expected state so it cannot race the consumer or `/confirm` |
| RF7 Real-time availability (sold + reserved) | `GetItem` on event | `GetItem` on inventory item; optional SSE stream |
| Java 25 / Boot 4.x / WebFlux | Yes / 4.0.3 / Yes | Yes / latest 4.0.x or 4.1.x / Yes; virtual threads only where there is real blocking |
| Reactive retry "when appropriate" | Present but ineffective | Correct: re-reads inside `Mono.defer`, filtered by cancellation reason, jittered backoff |
| SQS at-least-once + DLQ | Yes | Yes; idempotent consumer, `maxReceiveCount = 3` |
| Optimistic locking or conditional writes | Both, on the whole item | Conditional writes on the invariant only |
| Clean Architecture, SOLID, English code | Yes (per service) | Yes, single module tree; ArchUnit test forbids Spring/AWS imports in `domain` |
| Tests ≥ 90% incl. concurrency | JaCoCo gate; concurrency IT | Same gate; concurrency ITs for reserve, confirm and expire |
| `docker-compose.yml` | Yes | LocalStack (DynamoDB + SQS) + `api` + `worker` |
| Terraform (differentiator) | Modular | Kept; two ECS services from one image, VPC endpoints, IAM per role |

## Decision

1. **One deployable, two roles** from the same image:
   - `api`: WebFlux HTTP endpoints.
   - `worker`: SQS listener, outbox relay, expiry job.
   They scale independently (API by request load, worker by queue depth), which keeps the valid part of
   the v1 feedback (API and consumer should not compete for the same JVM) without splitting a single
   consistency boundary into services that share tables.
2. **Clean Architecture inside one module tree:** `domain` → `application` → `infrastructure`.
3. **Every state transition is one `TransactWriteItems`:** state change conditional on the expected
   `from` state + inventory counters + audit record (+ outbox item when a message is needed).
4. **Keep** what earned its place in v2: outbox, GSI on `status#expiresAt`, TTL, DLQ, JaCoCo gate,
   Terraform, Testcontainers concurrency tests.
5. **Remove** what does not: per-domain services, version-based locking, MDC, unjustified virtual
   threads, circuit breaker around DynamoDB (ADR 0004).
6. **Out of scope, declared as limitations:** authentication (ADR 0003), numbered seats (counters now,
   one item per seat if a seat map is required).

## Alternatives considered

- **Keep four microservices and fix the bugs.** Rejected: the transactions that prevent oversell span
  inventory, orders and audit. Splitting them forces either shared tables (current state) or a saga,
  which would be complexity with no requirement behind it.
- **Single process with HTTP and consumer together (v1).** Rejected: HTTP and queue processing have
  different load profiles and should scale separately.

## Consequences

- One codebase and one image to build, test and deploy; fewer moving parts to explain.
- The real scaling limit is the hot inventory item in DynamoDB, not the number of processes. It is
  measured in the load test and addressed as a documented improvement (sharded counters or a virtual
  waiting room).
- `docs/architecture`, `docs/decisions` and `docs/diagrams` describe v2 and will be rewritten for v3.

## What would change in production

- Outbox relay via DynamoDB Streams + EventBridge Pipes instead of polling.
- Real authentication (ADR 0003) and a payment provider webhook instead of a simulated `/confirm`.
- Sharded counters for events whose expected peak exceeds the per-item write limit.
