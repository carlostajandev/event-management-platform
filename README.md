<div align="center">

# Event Management Platform

**High-concurrency reactive ticketing platform — Nequi Technical Assessment**

[![CI/CD](https://github.com/carlostajandev/event-management-platform/actions/workflows/ci-cd.yml/badge.svg)](https://github.com/carlostajandev/event-management-platform/actions)
![Java](https://img.shields.io/badge/Java-21%20LTS-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.3-green)
![Tests](https://img.shields.io/badge/tests-65%20passing-brightgreen)
![Coverage](https://img.shields.io/badge/coverage-%3E90%25-brightgreen)
![Architecture](https://img.shields.io/badge/architecture-Clean%20Architecture-blue)
![IaC](https://img.shields.io/badge/IaC-Terraform-purple)

</div>

---

## Overview

A production-grade reactive ticketing platform built to handle concert ticket sales at scale — designed for high concurrency, financial consistency, and operational observability.

**Core guarantees:**
- **No overselling** — optimistic locking via DynamoDB conditional writes
- **No duplicate charges** — idempotency keys with 24h TTL
- **No data loss** — at-least-once SQS delivery with idempotent consumer
- **No single point of failure** — multi-AZ ECS Fargate + DynamoDB Global Tables

---

## Table of Contents

- [Architecture](#architecture)
- [Tech Stack](#tech-stack)
- [Prerequisites](#prerequisites)
- [Local Setup](#local-setup)
- [Running with Docker](#running-with-docker)
- [Running Tests](#running-tests)
- [API Reference](#api-reference)
- [Key Design Decisions](#key-design-decisions)
- [Project Structure](#project-structure)
- [Infrastructure (Terraform)](#infrastructure-terraform)
- [CI/CD Pipeline](#cicd-pipeline)
- [Documentation](#documentation)

---

## Architecture

### Clean Architecture — Layer Dependency Rules

```
┌──────────────────────────────────────────────────────────────────────┐
│                    HTTP Layer (WebFlux / Netty)                       │
│              Functional Routers + Handlers + Filters                  │
├──────────────────────────────────────────────────────────────────────┤
│                         INFRASTRUCTURE                                │
│                                                                       │
│  ┌──────────────┐  ┌─────────────────────┐  ┌─────────────────────┐ │
│  │  Web Layer   │  │    Persistence      │  │     Messaging       │ │
│  │  Handlers    │  │    DynamoDB         │  │  SQS Publisher      │ │
│  │  Routers     │  │    Mappers          │  │  SQS Consumer       │ │
│  │  Filters     │  │    Entities         │  │  Scheduler          │ │
│  └──────┬───────┘  └──────────┬──────────┘  └──────────┬──────────┘ │
└─────────┼────────────────────┼────────────────────────┼─────────────┘
          │ port/in            │ port/out               │ port/out
          ▼                    ▼                        ▼
┌──────────────────────────────────────────────────────────────────────┐
│                          APPLICATION                                  │
│                                                                       │
│  CreateEventService          ReserveTicketsService                    │
│  GetEventService             ProcessOrderService                      │
│  CreatePurchaseOrderService  ReleaseExpiredReservationsService        │
│  QueryOrderStatusService     AuditService                             │
│                                                                       │
│  RULE: Use cases depend only on domain interfaces (ports/out)         │
└─────────────────────────────┬────────────────────────────────────────┘
                              │ domain interfaces only
                              ▼
┌──────────────────────────────────────────────────────────────────────┐
│                            DOMAIN                                     │
│                  (zero external dependencies)                         │
│                                                                       │
│  Models:       Event, Ticket, Order, TicketStatus, OrderStatus        │
│  Value Objects: EventId, TicketId, OrderId, Money, Venue              │
│  Services:     TicketStateMachine                                     │
│  Exceptions:   EventNotFound, TicketNotAvailable, OrderNotFound       │
│  Repositories: Reactive interfaces (output ports)                     │
└──────────────────────────────────────────────────────────────────────┘

RULE: Arrows point inward only. Domain has NO imports from Spring, AWS, or any framework.
```

### Ticket Reservation Flow

```
Client          API (WebFlux)       Idempotency     Event       Ticket      SQS
  │                  │                  Repo         Repo        Repo        │
  │─POST /orders────►│                  │             │           │          │
  │  X-Idem-Key      │                  │             │           │          │
  │                  │─exists(key)?────►│             │           │          │
  │                  │◄─false───────────│             │           │          │
  │                  │─findById(evt)───────────────►  │           │          │
  │                  │◄─Event──────────────────────   │           │          │
  │                  │─findAvailable(N)────────────────────────►  │          │
  │                  │◄─[tkt_1, tkt_2]─────────────────────────   │          │
  │                  │─update(version=N+1)─────────────────────►  │          │
  │                  │  ConditionExpression            │           │          │
  │                  │◄─OK─────────────────────────────────────   │          │
  │                  │─save(key, resp)─►│              │           │          │
  │◄─201 RESERVED────│                  │              │           │          │
  │                  │─publish(order)──────────────────────────────────────►│
  │                  │                  │              │           │    async │
  │                  │             SqsOrderConsumer polls every 5s           │
  │                  │             RESERVED → PENDING_CONFIRMATION → SOLD    │
```

### Async Order Processing Flow

```
SqsConsumer    OrderRepo    TicketRepo    SQS
    │               │            │          │
    │─poll()────────────────────────────────►│
    │◄─[message]────────────────────────────│
    │─findById(ord)─►│            │          │
    │◄─Order(PENDING)│            │          │
    │─update(PROCESSING)►│        │          │
    │─findById(tkt1)──────────────►│         │
    │◄─Ticket(RESERVED)───────────│         │
    │─update(PENDING_CONF)────────►│         │
    │─update(SOLD)────────────────►│         │
    │─update(CONFIRMED)──►│        │          │
    │─deleteMessage()───────────────────────►│
```

### Ticket State Machine

```
                    ┌────────────────────────────┐
                    │                            │
              ┌─────▼──────┐            ┌────────▼────────┐
              │  AVAILABLE │            │  COMPLIMENTARY  │
              └─────┬──────┘            │    (FINAL)      │
                    │ reserve()         └─────────────────┘
                    ▼
              ┌─────────────┐
              │  RESERVED   │──── expiresAt < now ──────► AVAILABLE
              └─────┬───────┘         (scheduler)
                    │ confirmPending()
                    ▼
        ┌───────────────────────┐
        │  PENDING_CONFIRMATION │
        └──────────┬────────────┘
                   │
           ┌───────┴────────┐
           ▼                ▼
     ┌──────────┐    ┌───────────┐
     │   SOLD   │    │ AVAILABLE │
     │  (FINAL) │    │ (failed)  │
     └──────────┘    └───────────┘
```

### DynamoDB Data Model

```
┌─────────────────────────────────────────────────────────────┐
│  Table: emp-tickets                                          │
│  PK: ticketId (String)                                       │
│                                                             │
│  GSI: eventId-status-index                                  │
│  PK: eventId  |  SK: status                                 │
│  → Query "all AVAILABLE tickets for event X"                │
│     without full table scan                                 │
├─────────────────────────────────────────────────────────────┤
│  Table: emp-idempotency                                     │
│  PK: idempotencyKey (String)                                │
│  TTL: expiresAt (Unix epoch) — DynamoDB deletes expired     │
│       keys automatically. No cleanup job needed.            │
├─────────────────────────────────────────────────────────────┤
│  Table: emp-audit                                           │
│  PK: entityId (String)  |  SK: timestamp (String)           │
│  → All changes for an entity in chronological order         │
├─────────────────────────────────────────────────────────────┤
│  Table: emp-shedlock                                        │
│  PK: _id (String)                                           │
│  → Distributed scheduler lock across ECS instances          │
└─────────────────────────────────────────────────────────────┘
```

### Production AWS Architecture

```
                        ┌─────────────────────────────────────┐
                        │         Route 53 (DNS)               │
                        │   api.ticketing.nequi.com            │
                        └──────────────────┬──────────────────┘
                                           │
                        ┌──────────────────▼──────────────────┐
                        │    CloudFront + WAF                  │
                        │  Rate limiting · OWASP rules         │
                        └──────────────────┬──────────────────┘
                                           │
                        ┌──────────────────▼──────────────────┐
                        │  Application Load Balancer (ALB)    │
                        │  TLS termination · ACM certificate  │
                        │  HTTP → HTTPS redirect              │
                        └──────────┬──────────────────────────┘
                                   │
              ┌────────────────────┼────────────────────┐
              │                    │                    │
    ┌─────────▼──────┐   ┌─────────▼──────┐   ┌────────▼───────┐
    │  ECS Fargate   │   │  ECS Fargate   │   │  ECS Fargate   │
    │  Task (AZ-1a)  │   │  Task (AZ-1b)  │   │  Task (AZ-1c)  │
    │  Java 21 App   │   │  Java 21 App   │   │  Java 21 App   │
    └────────────────┘   └────────────────┘   └────────────────┘
              │                    │                    │
              └────────────────────┼────────────────────┘
                                   │
                    ┌──────────────┴──────────────┐
                    │                             │
         ┌──────────▼──────────┐     ┌────────────▼─────────┐
         │  DynamoDB           │     │  SQS                 │
         │  (6 tables)         │     │  purchase-orders     │
         │  PITR enabled       │     │  + DLQ               │
         │  Encryption at rest │     │  KMS encrypted       │
         └─────────────────────┘     └──────────────────────┘
```

---

## Tech Stack

| Component | Technology | Rationale |
|---|---|---|
| **Runtime** | Java 21 LTS | Chosen over Java 25 EA — AWS SDK v2, Resilience4j, and Testcontainers have certified support. Virtual Threads in final release. Java 25 is Early Access — not suitable for financial production systems. |
| **Framework** | Spring Boot 4.0.3 + WebFlux | Non-blocking I/O on Netty. Every DynamoDB/SQS call releases the thread immediately — handles far more concurrent connections than MVC with identical hardware. |
| **Serialization** | Jackson 3 (`tools.jackson`) | Spring Boot 4 migrated from Jackson 2. `JsonMapper` (concrete) injected directly — eliminates Spring bean ambiguity when resolving `ObjectMapper` (abstract). |
| **Database** | DynamoDB (PAY_PER_REQUEST) | P99 predictable latency. Native TTL for idempotency key expiry (no cleanup job). GSI for efficient ticket queries. PITR for financial data recovery. |
| **Messaging** | SQS Standard | At-least-once delivery. DLQ after 3 failures. Long polling (20s) reduces empty receives by ~95%. Decouples reservation (sync, fast) from payment processing (async, retryable). |
| **Resilience** | Resilience4j 2.3.0 | `@CircuitBreaker` on all DynamoDB repositories and SQS publisher. `@Retry` with exponential backoff on SQS publish. Prevents cascade failures. |
| **Observability** | Micrometer + Prometheus | Metrics at `/actuator/prometheus`. `X-Correlation-Id` propagated through `CorrelationIdFilter` into MDC — every log statement includes the trace ID. |
| **Distributed Lock** | ShedLock 6.0.1 + DynamoDB | Prevents N ECS instances from running the reservation expiry scheduler N times. `lockAtMostFor=55s` releases lock even on instance crash. |
| **IaC** | Terraform 1.7+ | 5 modules: `networking`, `dynamodb`, `sqs`, `ecs`, `iam`. IAM least privilege — execution role and task role separated. |

---

## Prerequisites

| Tool | Version | Install |
|---|---|---|
| Java | 21 LTS | [Temurin](https://adoptium.net/) |
| Docker | 20.10+ | [Docker Desktop](https://www.docker.com/products/docker-desktop/) |
| Docker Compose | 2.0+ | Included with Docker Desktop |
| Maven | 3.9+ | Or use `./mvnw` wrapper (included) |

No AWS account required for local development.

---

## Local Setup

### 1. Clone

```bash
git clone https://github.com/carlostajandev/event-management-platform.git
cd event-management-platform
```

### 2. Start local infrastructure

```bash
docker compose up -d
```

This starts:
- **DynamoDB Local 1.25.0** on `http://localhost:8000`
- **LocalStack 3.6** (SQS) on `http://localhost:4566`

Verify healthy:

```bash
docker compose ps
# NAME              STATUS
# dynamodb-local    Up (healthy)
# localstack        Up (healthy)
```

### 3. Start the application

```bash
./mvnw spring-boot:run
```

DynamoDB tables are created automatically on startup:

```
✓ emp-events
✓ emp-tickets       (GSI: eventId-status-index)
✓ emp-orders        (GSI: userId-index)
✓ emp-idempotency   (TTL: expiresAt)
✓ emp-audit         (PK: entityId, SK: timestamp)
```

### 4. Verify

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

### Environment configuration

`src/main/resources/application-local.yml` — no real AWS credentials needed:

```yaml
aws:
  access-key-id: fakeMyKeyId
  secret-access-key: fakeSecretAccessKey
  dynamodb:
    endpoint: http://127.0.0.1:8000
  sqs:
    endpoint: http://127.0.0.1:4566
```

---

## Running with Docker

```bash
# Start full stack (infrastructure + application)
docker compose up -d

# Stream application logs
docker compose logs -f app

# Stop everything
docker compose down

# Stop and remove volumes (clean slate)
docker compose down -v
```

### docker-compose.yml overview

```yaml
services:
  dynamodb-local:   # DynamoDB Local 1.25.0 — port 8000
  localstack:       # LocalStack 3.6 (SQS) — port 4566
  app:              # Spring Boot 4 application — port 8080
```

---

## Running Tests

```bash
# Full suite with JaCoCo coverage report
./mvnw test

# Verify + coverage gate (fails if below threshold)
./mvnw verify

# Specific test
./mvnw test -Dtest="TicketStateMachineTest"

# Multiple tests
./mvnw test -Dtest="TicketStateMachineTest,ReserveTicketsServiceTest,AuditServiceTest"
```

### Test results — 65 tests, 0 failures

| Test Suite | Tests | Coverage Focus |
|---|---|---|
| `EventDomainTest` | 8 | Event model validation, business rules |
| `TicketDomainTest` | 7 | Ticket model, state transitions |
| `TicketStateMachineTest` | 17 | All valid/invalid state transitions |
| `CreateEventServiceTest` | 3 | Event creation, validation |
| `ReserveTicketsServiceTest` | 5 | Reservation, idempotency, concurrent modification |
| `ProcessOrderServiceTest` | 4 | Order processing, idempotent consumer |
| `ReleaseExpiredReservationsServiceTest` | 3 | Expiry scheduler logic |
| `CreatePurchaseOrderServiceTest` | 2 | Order creation + SQS publish |
| `AuditServiceTest` | 4 | Audit trail, failure absorption |
| `CorrelationIdFilterTest` | 2 | HTTP filter, MDC propagation |
| `EventHandlerTest` | 5 | HTTP layer, `@WebFluxTest` slice |
| `OrderHandlerTest` | 4 | HTTP layer, idempotency header validation |
| `EventManagementPlatformApplicationTests` | 1 | Full Spring context load |

---

## API Reference

Base URL: `http://localhost:8080`

### Events

#### `POST /api/v1/events` — Create Event

```bash
curl -s -X POST http://localhost:8080/api/v1/events \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Bad Bunny World Tour 2027",
    "description": "The concert of the year in Bogotá",
    "eventDate": "2027-06-15T20:00:00Z",
    "venueName": "Estadio El Campín",
    "venueCity": "Bogotá",
    "venueCountry": "Colombia",
    "totalCapacity": 50000,
    "ticketPrice": 350000,
    "currency": "COP"
  }' | jq '.'
```

**Response `201 Created`:**
```json
{
  "eventId": "evt_a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "name": "Bad Bunny World Tour 2027",
  "status": "DRAFT",
  "availableTickets": 50000,
  "ticketPrice": 350000,
  "currency": "COP"
}
```

---

#### `GET /api/v1/events/{eventId}` — Get Event

```bash
curl -s http://localhost:8080/api/v1/events/evt_a1b2c3d4 | jq '.'
```

**Response `404 Not Found`:**
```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Event not found: evt_unknown",
  "path": "/api/v1/events/evt_unknown",
  "timestamp": "2027-06-01T10:00:00Z"
}
```

---

#### `GET /api/v1/events` — List Events (paginated)

```bash
curl -s "http://localhost:8080/api/v1/events?page=0&size=10" | jq '.'
```

**Response `200 OK`:**
```json
{
  "items": [{ "eventId": "...", "name": "...", "status": "DRAFT" }],
  "page": 0,
  "size": 10,
  "hasMore": false
}
```

---

#### `GET /api/v1/events/{eventId}/availability` — Real-time Availability

```bash
curl -s http://localhost:8080/api/v1/events/evt_a1b2c3d4/availability | jq '.'
```

**Response `200 OK`:**
```json
{
  "eventId": "evt_a1b2c3d4",
  "availableTickets": 49998,
  "reservedTickets": 2,
  "soldTickets": 0,
  "totalCapacity": 50000,
  "isAvailable": true
}
```

---

### Orders

#### `POST /api/v1/orders` — Reserve Tickets

> **Required header:** `X-Idempotency-Key` — a client-generated UUID.
> Retrying with the **same key** returns the cached response. No duplicate reservation is created.

```bash
curl -s -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: $(uuidgen)" \
  -d '{
    "eventId": "evt_a1b2c3d4",
    "userId": "usr_test_001",
    "quantity": 2
  }' | jq '.'
```

**Response `201 Created`:**
```json
{
  "orderId": "ord_x1y2z3w4-...",
  "eventId": "evt_a1b2c3d4",
  "userId": "usr_test_001",
  "quantity": 2,
  "totalAmount": 700000,
  "currency": "COP",
  "status": "RESERVED",
  "reservedAt": "2027-06-01T10:00:00Z",
  "expiresAt": "2027-06-01T10:10:00Z"
}
```

**Response `400 Bad Request`** — missing idempotency key:
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "X-Idempotency-Key header is required"
}
```

**Response `409 Conflict`** — no tickets available:
```json
{
  "status": 409,
  "error": "Conflict",
  "message": "No available tickets for event: evt_a1b2c3d4"
}
```

---

#### `GET /api/v1/orders/{orderId}` — Get Order Status

```bash
curl -s http://localhost:8080/api/v1/orders/ord_x1y2z3w4 | jq '.'
```

**Response `200 OK`:**
```json
{
  "orderId": "ord_x1y2z3w4-...",
  "eventId": "evt_a1b2c3d4",
  "userId": "usr_test_001",
  "quantity": 2,
  "totalAmount": 700000,
  "currency": "COP",
  "status": "CONFIRMED"
}
```

---

### Actuator

```bash
# Health check
curl -s http://localhost:8080/actuator/health | jq '.status'

# Prometheus metrics
curl -s http://localhost:8080/actuator/prometheus | grep http_server

# Environment
curl -s http://localhost:8080/actuator/env | jq '.activeProfiles'
```

---

### Error Reference

| Status | Scenario |
|---|---|
| `400` | Missing `X-Idempotency-Key`, validation failure, invalid pagination params |
| `404` | Event or order not found |
| `409` | No tickets available for the requested quantity |
| `500` | Internal error — never exposes stack traces, internal IPs, or table names |

---

## Key Design Decisions

### 1. Java 21 LTS — not Java 25 Early Access

Java 25 is in Early Access. AWS SDK v2, Resilience4j, and Testcontainers have no certified support against it. In a ticketing system handling financial transactions under high concurrency, stability is non-negotiable. Java 21 LTS provides Virtual Threads (final release), Records, Pattern Matching, and Sealed Classes — all the modern language features needed, with a stable ecosystem.

**In a technical review:** *"I chose Java 21 LTS deliberately. Java 25 is Early Access — the ecosystem including AWS SDK v2, Resilience4j, and Testcontainers does not have certified support yet. For a system processing financial transactions under high concurrency, stability is not negotiable."*

### 2. Jackson 3 — `JsonMapper` (concrete) over `ObjectMapper` (abstract)

Spring Boot 4 migrated from Jackson 2 (`com.fasterxml.jackson`) to Jackson 3 (`tools.jackson`). The framework registers `JsonMapper` as a bean — not `ObjectMapper`. Injecting the concrete type directly is more explicit, type-safe, and eliminates the `NoUniqueBeanDefinitionException` that occurs when Spring resolves the abstract `ObjectMapper` and finds multiple candidates.

### 3. DynamoDB as single store

One store for tickets, orders, idempotency keys, audit log, and ShedLock. DynamoDB provides: native TTL (idempotency keys expire automatically — no cleanup Lambda), sub-millisecond P99 latency, auto-scaling without manual partitioning, and PITR for 35-day point-in-time recovery of financial data.

### 4. Optimistic locking — no distributed lock

Every ticket update includes `ConditionExpression: version = N`. If two concurrent requests compete for the same ticket, only one wins — DynamoDB rejects the second with `ConditionalCheckFailedException`, mapped to `409 Conflict`. No Redis, no pessimistic lock, no coordination overhead. Scales horizontally with no contention.

### 5. SQS for async order processing

Decouples reservation (synchronous, sub-100ms) from payment processing (asynchronous, retryable). The consumer is fully idempotent — if the same order is processed twice, the second attempt detects the final state and skips silently. SQS retries automatically up to 3 times before moving to DLQ.

### 6. WebFlux over Spring MVC

Every DynamoDB or SQS call releases the thread immediately via Project Reactor. With identical hardware, WebFlux handles significantly more concurrent connections than thread-per-request MVC. Critical for ticket sale spikes where thousands of users hit `POST /orders` simultaneously.

### 7. ShedLock for distributed scheduler

`@Scheduled` without coordination would run the expiry job on every ECS instance simultaneously — releasing the same expired tickets multiple times, causing race conditions. ShedLock uses DynamoDB as a distributed lock (`lockAtMostFor=55s`, `lockAtLeastFor=30s`) — only one instance runs per cycle, even across rolling deployments.

---

## Project Structure

```
event-management-platform/
│
├── src/main/java/com/nequi/ticketing/
│   │
│   ├── domain/                         # INNER — zero external dependencies
│   │   ├── model/                      # Event, Ticket, Order, TicketStatus, OrderStatus
│   │   ├── valueobject/                # EventId, TicketId, OrderId, Money, Venue
│   │   ├── repository/                 # Reactive interfaces (output ports)
│   │   ├── service/                    # TicketStateMachine
│   │   └── exception/                  # EventNotFound, TicketNotAvailable, OrderNotFound
│   │
│   ├── application/                    # MIDDLE — depends on domain only
│   │   ├── usecase/                    # One class per use case
│   │   ├── port/in/                    # Input ports (driving interfaces)
│   │   ├── port/out/                   # Output ports (driven interfaces)
│   │   └── dto/                        # Request/Response DTOs
│   │
│   └── infrastructure/                 # OUTER — implements ports
│       ├── config/                     # DynamoDbConfig, SqsConfig, ShedLockConfig, CorsConfig
│       ├── persistence/dynamodb/       # Entities, Mappers, DynamoDB repositories
│       ├── messaging/sqs/              # SqsMessagePublisher, SqsOrderConsumer
│       ├── scheduler/                  # ExpiredReservationScheduler (@SchedulerLock)
│       ├── web/
│       │   ├── filter/                 # CorrelationIdFilter
│       │   ├── handler/                # EventHandler, OrderHandler, AvailabilityHandler
│       │   └── router/                 # EventRouter, OrderRouter (functional routing)
│       └── shared/error/               # GlobalErrorHandler
│
├── terraform/                          # Infrastructure as Code
│   ├── modules/networking/             # VPC, subnets, NAT, VPC endpoints, security groups
│   ├── modules/dynamodb/               # 6 tables, GSIs, TTL, PITR, encryption
│   ├── modules/sqs/                    # Queue + DLQ + CloudWatch alarm
│   ├── modules/ecs/                    # Fargate, ALB, auto-scaling
│   ├── modules/iam/                    # Least-privilege execution + task roles
│   └── environments/                  # prod.tfvars, dev.tfvars
│
├── docs/
│   ├── architecture/ARCHITECTURE.md   # Diagrams, sequences, state machine
│   └── decisions/
│       ├── SECURITY.md                # IAM, idempotency attacks, error hardening
│       ├── TRADE_OFFS.md              # Limitations and production improvements
│       └── CLOUD_NATIVE.md           # VPC design, costs, DR, observability
│
├── .github/workflows/ci-cd.yml        # GitHub Actions pipeline
├── docker-compose.yml                 # DynamoDB Local + LocalStack
├── api-requests.sh                    # Full curl test suite
└── postman/                           # Postman collection with assertions
```

---

## Infrastructure (Terraform)

5 production-ready modules:

| Module | Resources | Key Decisions |
|---|---|---|
| `networking` | VPC, public/private subnets, NAT, VPC endpoints | Traffic to DynamoDB/SQS/ECR stays within AWS — no NAT cost, no public internet |
| `dynamodb` | 6 tables, GSIs, TTL, PITR, encryption | PAY_PER_REQUEST handles spikes without capacity planning |
| `sqs` | Queue + DLQ + CloudWatch alarm | Alert immediately on DLQ messages — order processing failures |
| `ecs` | Fargate, ALB HTTPS, rolling deploy 50/200%, auto-scaling | Scale out in 60s, scale in in 300s. Health check on `/actuator/health` |
| `iam` | Execution role + Task role | No wildcards in resources. DynamoDB access scoped to 6 tables only |

```bash
cd terraform
terraform init
terraform plan  -var-file=environments/prod.tfvars
terraform apply -var-file=environments/prod.tfvars
```

See [terraform/TERRAFORM.md](terraform/TERRAFORM.md) — decisions, commands, cost breakdown (~$102/month production).

---

## CI/CD Pipeline

`.github/workflows/ci-cd.yml` — GitHub Actions:

```
Pull Request → [Build & Test] + [Terraform Validate]
Push main    → above + [Docker Build & Push] + [Terraform Plan]
Tag v*       → above + [Deploy Production] ← requires manual approval
```

| Job | Trigger | What it does |
|---|---|---|
| Build & Test | All events | `./mvnw verify` — 65 tests + JaCoCo gate |
| Terraform Validate | All events | `terraform fmt -check` + `terraform validate` (no AWS credentials needed) |
| Docker Build & Push | `main`, `v*` tags | JAR → Docker image → ECR push |
| Terraform Plan | `main` | `terraform plan` against staging environment |
| Deploy Production | `v*` tags | `terraform apply` + ECS wait-stable + smoke test on `/actuator/health` |

---

## Documentation

| Document | Description |
|---|---|
| [docs/architecture/ARCHITECTURE.md](docs/architecture/ARCHITECTURE.md) | Clean Architecture diagrams, sequence flows, state machine, DynamoDB model |
| [docs/decisions/SECURITY.md](docs/decisions/SECURITY.md) | IAM least privilege, idempotency attack prevention, input validation, error hardening |
| [docs/decisions/TRADE_OFFS.md](docs/decisions/TRADE_OFFS.md) | Known limitations, what would change in production, future improvements |
| [docs/decisions/CLOUD_NATIVE.md](docs/decisions/CLOUD_NATIVE.md) | VPC design, cost estimates ($102/mo), observability three pillars, DR strategy |
| [terraform/TERRAFORM.md](terraform/TERRAFORM.md) | IaC decisions, Terraform commands, cost breakdown |
| [api-requests.sh](api-requests.sh) | Full curl test suite — idempotency verification, all error scenarios |
| [postman/](postman/) | Postman collection with automated assertions for all endpoints |