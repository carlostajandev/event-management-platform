<div align="center">

# Event Management Platform v2

**Reactive ticketing platform — Microservices Monorepo**
**Technical assessment for Nequi (Staff/Senior level)**

[![CI/CD](https://github.com/carlostajandev/event-management-platform/actions/workflows/ci-cd.yml/badge.svg)](https://github.com/carlostajandev/event-management-platform/actions)
![Java](https://img.shields.io/badge/Java-25-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.3-green)
![Architecture](https://img.shields.io/badge/Architecture-Microservices-blue)
![Tests](https://img.shields.io/badge/tests-44%2B%20passing-brightgreen)
![Coverage](https://img.shields.io/badge/coverage-%E2%89%A590%25-brightgreen)

</div>

---

## Architecture

```
event-management-platform/
├── shared/
│   ├── domain/           ← Java 25 Records, Ports, Domain Events (zero framework deps)
│   └── infrastructure/   ← DynamoDB/SQS config, MDC filter, error handler
├── services/
│   ├── event-service/    ← port 8081 — CRUD events + atomic inventory counter
│   ├── reservation-service/ ← port 8082 — conditional writes + TTL expiry
│   ├── order-service/    ← port 8083 — transactional outbox + idempotency
│   └── consumer-service/ ← port 8084 — @SqsListener + OutboxPoller + audit
├── infrastructure/
│   ├── docker-compose.yml  ← full stack including Prometheus + Grafana
│   ├── terraform/          ← IaC for AWS (VPC, DynamoDB, SQS, ECS Fargate, IAM)
│   └── localstack/         ← init-aws.sh (tables + queues on startup)
└── docs/
    ├── architecture/ARCHITECTURE.md
    ├── sequence-diagram.md
    ├── state-machine.md
    └── data-model.md
```

## Quickstart (Local)

```bash
# 1. Start LocalStack + all services + observability stack
docker compose -f infrastructure/docker-compose.yml up -d

# 2. Verify health
curl http://localhost:8081/actuator/health  # event-service
curl http://localhost:8082/actuator/health  # reservation-service
curl http://localhost:8083/actuator/health  # order-service
curl http://localhost:8084/actuator/health  # consumer-service

# 3. Grafana dashboard → http://localhost:3000 (admin/admin)
# 4. Prometheus        → http://localhost:9090
```

## Run Unit Tests

```bash
# All 4 services — no Docker required (<30s)
./mvnw test

# With integration tests (requires Docker)
./mvnw verify

# Single service
./mvnw test -pl services/event-service
```

## API Quick Reference

### Create Event
```bash
curl -X POST http://localhost:8081/api/v1/events \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: $(uuidgen)" \
  -d '{
    "name": "Rock Fest 2026",
    "description": "Annual rock music festival",
    "venue": {"name":"Movistar Arena","address":"Cra 37","city":"Bogotá","country":"Colombia","capacity":5000},
    "eventDate": "2026-12-15T20:00:00Z",
    "ticketPrice": 150000.00,
    "currency": "COP",
    "totalCapacity": 100
  }'
```

### Reserve Tickets (atomic conditional write)
```bash
curl -X POST http://localhost:8082/api/v1/reservations \
  -H "Content-Type: application/json" \
  -d '{"eventId": "<eventId>", "userId": "user-123", "seatsCount": 2}'
```

### Create Order (idempotent)
```bash
curl -X POST http://localhost:8083/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: $(uuidgen)" \
  -d '{"reservationId": "<reservationId>", "userId": "user-123"}'
```

### Query Order Status
```bash
curl http://localhost:8083/api/v1/orders/<orderId>
```

## Key Design Decisions

### 1. No Overselling — Conditional Writes
DynamoDB `UpdateItem` with `ConditionExpression: availableCount >= :n AND version = :expected`.
On `ConditionalCheckFailedException`: `retryWhen(Retry.backoff(3, 100ms))` → 409 Conflict.

**Why not distributed locks (Redis)?**
No external lock service, no SPOF. DynamoDB handles atomicity natively. O(1) regardless of concurrency.

### 2. Transactional Outbox — Zero Zombie Orders
`order-service` writes order + outbox message in a single `TransactWriteItems` call.
If SQS is down, the OutboxPoller (consumer-service) retries every 5 seconds until SQS recovers.

**Without outbox (old code):** `save(order)` then `publish(message)` → if SQS fails, order is PENDING forever.
**With outbox:** atomicity guaranteed — either both succeed or both fail.

### 3. TTL-Based Reservation Expiry — O(1) vs O(table)
`ttl = now + 600 seconds` (epoch). DynamoDB auto-deletes expired items at zero compute cost.
In production: DynamoDB Streams → Lambda releases inventory.
In local dev (LocalStack): reconciliation scheduler queries GSI `STATUS#ACTIVE / expiresAt <= now` — O(results), not O(table).

**Why not @Scheduled + scan?**
Old code scanned the entire table on every cycle. O(table) = unusable at scale.
New code queries a GSI with a sort key range condition. O(results) = scales to millions.

### 4. Idempotency — No Duplicate Orders
`X-Idempotency-Key` header (client UUID) cached in `emp-idempotency-keys` with 24h TTL.
Duplicate request → cached response returned immediately. No business logic re-executed.

### 5. AuditService — Fully Integrated
Every state transition (NONE→ACTIVE, ACTIVE→CONFIRMED, PENDING→CONFIRMED) writes to `emp-audit`.
Previously: AuditService was implemented but never called. Now: called in reservation-service and consumer-service on every state change.

### 6. Java 25 Features Used
- **Records**: all domain models and DTOs (immutable, no Lombok needed)
- **Pattern Matching Switch**: `GlobalErrorHandler`, `DomainEvent` routing
- **Sealed Interfaces**: `DomainEvent` with permits clause — exhaustive switch without default
- **Virtual Threads**: `spring.threads.virtual.enabled: true` in all services

## Observability

| Metric | Type | Service |
|---|---|---|
| `events.created.total` | Counter | event-service |
| `event.available_tickets{eventId}` | Gauge | event-service |
| `tickets.reserved.total` | Counter | reservation-service |
| `reservations.cancelled.total` | Counter | reservation-service |
| `reservation.expired.released` | Counter | reservation-service |
| `orders.created.total` | Counter | order-service |
| `orders.processed.total` | Counter | consumer-service |
| `order.processing.duration` | Timer | consumer-service |

All logs are structured JSON (logstash-logback-encoder) with `traceId` (from `X-Correlation-Id` header) for distributed tracing in CloudWatch/ELK.

## Infrastructure (Terraform)

```bash
cd infrastructure/terraform

# Dev environment
terraform init
terraform plan -var-file=environments/dev.tfvars
terraform apply -var-file=environments/dev.tfvars

# Resources created:
# - VPC multi-AZ with private subnets + NAT Gateway
# - 6 DynamoDB tables (PAY_PER_REQUEST, TTL, PITR, encryption)
# - 2 SQS queues (purchase-orders + DLQ, long polling, KMS)
# - IAM roles (execution + task, least-privilege)
# - ECS Fargate cluster + ALB (4 services, rolling deploy)
```

## Answers to Evaluator Questions

**Q: How do you guarantee no overselling under 10,000 concurrent requests?**
Conditional write with version counter on the event item. `availableCount >= n AND version = expected`. If the condition fails (concurrent write won), retry with exponential backoff (100ms, 200ms, 400ms, max 3 retries), then 409 Conflict. Proven in `TicketReservationConcurrencyTest`.

**Q: What happens if DynamoDB accepts the order but SQS is down?**
Transactional outbox: `TransactWriteItems([order, outboxMessage])`. Both written atomically or neither. `OutboxPoller` runs every 5s — retries SQS publish until it succeeds. Zero zombie orders.

**Q: How do your microservices scale horizontally?**
All services are stateless. State lives in DynamoDB and SQS. `consumer-service` scales with SQS queue depth. SQS distributes messages across instances automatically. ECS auto-scaling configured in Terraform.

**Q: Why TTL instead of scheduler for expiry?**
O(1) vs O(table). DynamoDB TTL is a background process — zero compute, zero cost, scales to millions of reservations. A scheduler scanning the full table would be O(table) = unusable at scale (the previous code had this bug).

**Q: Known limitations?**
- DynamoDB Streams limited in LocalStack → reconciliation scheduler as fallback
- No virtual waiting room for sold-out events under bursty load (future improvement)
- Write sharding for popular events is documented but not fully wired in default path
- Terraform uses simple ECS rolling deploy, not blue-green (documented trade-off)
