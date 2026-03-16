<div align="center">

# Event Management Platform

**Reactive ticketing platform built as a technical assessment for Nequi**

[![CI/CD](https://github.com/carlostajandev/event-management-platform/actions/workflows/ci-cd.yml/badge.svg)](https://github.com/carlostajandev/event-management-platform/actions)
![Java](https://img.shields.io/badge/Java-25-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.3-green)
![Tests](https://img.shields.io/badge/tests-65%2B%20passing-brightgreen)
![Coverage](https://img.shields.io/badge/coverage-%E2%89%A580%25%20line%20%7C%20%E2%89%A570%25%20branch-brightgreen)

</div>

---

## Overview

A high-concurrency reactive ticketing platform designed to handle concert ticket sales at scale. Built with **Clean Architecture**, **Spring WebFlux** (non-blocking I/O), **DynamoDB** for persistence, and **SQS** for async order processing.

The system guarantees **no overselling** through optimistic locking with DynamoDB conditional writes, and **no duplicate charges** through idempotency keys with TTL-based expiry.

---

## Table of Contents

- [Architecture](#architecture)
- [Tech Stack](#tech-stack)
- [Prerequisites](#prerequisites)
- [Local Setup](#local-setup)
- [Running the Application](#running-the-application)
- [Running Tests](#running-tests)
- [API Reference](#api-reference)
- [Key Design Decisions](#key-design-decisions)
- [Project Structure](#project-structure)
- [Infrastructure](#infrastructure)
- [CI/CD](#cicd)
- [Documentation](#documentation)

---

## Architecture

The application follows **Clean Architecture** with strict dependency rules — inner layers never depend on outer layers.

```
┌──────────────────────────────────────────────────────────────────┐
│                     HTTP (WebFlux / Netty)                        │
│                  Functional Routers + Handlers                    │
├──────────────────────────────────────────────────────────────────┤
│                        INFRASTRUCTURE                             │
│                                                                   │
│  ┌─────────────┐  ┌──────────────────┐  ┌─────────────────────┐ │
│  │  Web Layer  │  │   Persistence    │  │     Messaging       │ │
│  │  Handlers   │  │   DynamoDB       │  │  SQS Publisher      │ │
│  │  Routers    │  │   Mappers        │  │  SQS Consumer       │ │
│  │  Filters    │  │   Entities       │  │  Scheduler          │ │
│  └──────┬──────┘  └───────┬──────────┘  └──────────┬──────────┘ │
│         │                 │                         │            │
└─────────┼─────────────────┼─────────────────────────┼────────────┘
          │ port/in         │ port/out                │ port/out
┌─────────▼─────────────────▼─────────────────────────▼────────────┐
│                        APPLICATION                                │
│                                                                   │
│   CreateEventService       ReserveTicketsService                  │
│   GetEventService          ProcessOrderService                    │
│   CreatePurchaseOrderService  ReleaseExpiredReservationsService   │
│   QueryOrderStatusService  AuditService                           │
└─────────────────────────────┬────────────────────────────────────┘
                              │ domain interfaces
┌─────────────────────────────▼────────────────────────────────────┐
│                          DOMAIN                                   │
│                  (zero external dependencies)                     │
│                                                                   │
│   Models: Event, Ticket, Order, TicketStatus, OrderStatus         │
│   Value Objects: EventId, TicketId, OrderId, Money, Venue         │
│   Services: TicketStateMachine                                    │
│   Exceptions: EventNotFound, TicketNotAvailable, OrderNotFound    │
└──────────────────────────────────────────────────────────────────┘
```

### Purchase Flow

```
POST /api/v1/orders
        │
        ▼
  Validate event ──────────────────── 404 EventNotFoundException
        │
        ▼
  Check availability ──────────────── 409 TicketNotAvailableException
        │
        ▼
  Reserve tickets (optimistic lock)
  DynamoDB conditional write
  version = expectedVersion
        │
        ▼
  Cache idempotency key (TTL 24h)
        │
        ▼
  Save order PENDING ──── 201 RESERVED (immediate response)
        │
        ▼
  Publish to SQS (async)
        │
        ▼
  SqsOrderConsumer polls every 5s
        │
        ▼
  RESERVED → PENDING_CONFIRMATION → SOLD
        │
        ▼
  Order CONFIRMED
```

### Ticket State Machine

```
AVAILABLE ────────────────────────── COMPLIMENTARY (final)
    │
    ▼
RESERVED ──── expiresAt < now ──────► AVAILABLE (released by scheduler)
    │
    ▼
PENDING_CONFIRMATION
    │
    ├──── payment ok ───────────────► SOLD (final)
    │
    └──── payment failed ───────────► AVAILABLE
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

## Tech Stack

| Component | Technology | Decision |
|---|---|---|
| **Runtime** | Java 25 | Requisito explícito de la prueba. Virtual Threads (GA), Pattern Matching, Sealed Classes, Records — todos GA. |
| **Framework** | Spring Boot 4.0.3 + WebFlux | Non-blocking I/O on Netty. Superior throughput vs MVC for DynamoDB/SQS intensive workloads. |
| **Serialization** | Jackson 3 (`tools.jackson`) | SB4 migrated from Jackson 2. `JsonMapper` (concrete) injected directly — avoids Spring bean ambiguity with `ObjectMapper` (abstract). |
| **Database** | DynamoDB | P99 predictable latency, native TTL for idempotency keys, auto-scaling, single-table design. |
| **Messaging** | SQS | At-least-once delivery, DLQ, decouples reservation (sync) from payment processing (async). |
| **Resilience** | Resilience4j | `@CircuitBreaker` on all DynamoDB repos and SQS publisher. `@Retry` on SQS publish. |
| **Observability** | Micrometer + Prometheus | Metrics at `/actuator/prometheus`. CorrelationId propagated in MDC for all log statements. |
| **Distributed Lock** | ShedLock + DynamoDB | Prevents N ECS instances from running the expiry scheduler N times simultaneously. |
| **IaC** | Terraform | 5 modules: networking, dynamodb, sqs, ecs, iam — production-ready AWS deployment. |

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Java | 21 LTS | [Temurin distribution](https://adoptium.net/) recommended |
| Docker | 20.10+ | Required for DynamoDB Local + LocalStack |
| Docker Compose | 2.0+ | Included with Docker Desktop |
| Maven | 3.9+ | Or use the included `./mvnw` wrapper |

---

## Local Setup

### 1. Clone the repository

```bash
git clone https://github.com/carlostajandev/event-management-platform.git
cd event-management-platform
```

### 2. Start local infrastructure

```bash
docker compose up -d
```

This starts:
- **DynamoDB Local** on `http://localhost:8000`
- **LocalStack** (SQS) on `http://localhost:4566`

Verify everything is healthy:

```bash
docker compose ps
# Both services should show "healthy"
```

### 3. Start the application

```bash
./mvnw spring-boot:run
```

The application starts on `http://localhost:8080`. DynamoDB tables are created automatically on startup via `DynamoDbTableInitializer`.

```
Tables created:
  emp-events
  emp-tickets
  emp-orders
  emp-idempotency
  emp-audit
```

### Environment configuration

Local settings live in `src/main/resources/application-local.yml`:

```yaml
aws:
  access-key-id: fakeMyKeyId          # No real credentials needed locally
  secret-access-key: fakeSecretAccessKey
  dynamodb:
    endpoint: http://127.0.0.1:8000
  sqs:
    endpoint: http://127.0.0.1:4566
```

No real AWS credentials are needed for local development.

---

## Running the Application

### With Maven (development)

```bash
./mvnw spring-boot:run
```

### With Docker Compose (full stack)

```bash
# Start infrastructure + application
docker compose up -d

# View logs
docker compose logs -f app

# Stop everything
docker compose down
```

### Verify the application is running

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

---

## Running Tests

```bash
# ── Unit tests only (no Docker required) ────────────────────────────────────
./mvnw test

# ── Unit + Integration tests (requires Docker for LocalStack) ────────────────
./mvnw verify

# ── Specific unit test ───────────────────────────────────────────────────────
./mvnw test -Dtest="TicketStateMachineTest"

# ── Specific integration test ────────────────────────────────────────────────
./mvnw failsafe:integration-test -Dit.test="TicketReservationConcurrencyIT"

# ── Coverage report (target/site/jacoco/index.html) ─────────────────────────
./mvnw verify   # gate: ≥80% line coverage, ≥70% branch coverage
```

> **Note:** Integration tests (`*IT.java`) require Docker running — they launch a LocalStack container with DynamoDB + SQS via Testcontainers.
> Unit tests (`*Test.java`, `*Tests.java`) have zero infrastructure dependencies.

### Test Matrix

**Unit tests — 65 passing, no Docker needed**

| Test Suite | Tests | Description |
|---|---|---|
| `EventDomainTest` | 8 | Event model validations |
| `TicketDomainTest` | 7 | Ticket model validations |
| `TicketStateMachineTest` | 17 | All valid and invalid state transitions |
| `CreateEventServiceTest` | 3 | Event creation use case |
| `ReserveTicketsServiceTest` | 5 | Reservation, idempotency, compensation |
| `ProcessOrderServiceTest` | 4 | Order processing, idempotency |
| `ReleaseExpiredReservationsServiceTest` | 3 | Expiry scheduler |
| `CreatePurchaseOrderServiceTest` | 2 | Order creation + SQS publish |
| `AuditServiceTest` | 4 | Audit trail, failure absorption |
| `CorrelationIdFilterTest` | 2 | HTTP filter, MDC propagation |
| `EventHandlerTest` | 5 | HTTP layer, WebFlux slice |
| `OrderHandlerTest` | 4 | HTTP layer, WebFlux slice |
| `EventManagementPlatformApplicationTests` | 1 | Spring context smoke test |

**Integration tests — requires Docker + LocalStack**

| Test Suite | Tests | Description |
|---|---|---|
| `TicketReservationConcurrencyIT` | 3 | 100 concurrent requests / 50 tickets — zero overselling proof |
| `AvailabilityIT` | 5 | Real-time availability endpoint consistency |
| `OrderProcessingIT` | 4 | Full async flow: reserve → SQS → CONFIRMED + SOLD |

---

## API Reference

### Events

#### Create Event

```bash
curl -X POST http://localhost:8080/api/v1/events \
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
  }'
```

**Response 201:**
```json
{
  "eventId": "evt_a1b2c3d4-...",
  "name": "Bad Bunny World Tour 2027",
  "status": "DRAFT",
  "availableTickets": 50000
}
```

#### Get Event

```bash
curl http://localhost:8080/api/v1/events/evt_a1b2c3d4
```

#### List Events (paginated)

```bash
curl "http://localhost:8080/api/v1/events?page=0&size=20"
```

**Response 200:**
```json
{
  "items": [...],
  "page": 0,
  "size": 20,
  "hasMore": false
}
```

#### Get Availability

```bash
curl http://localhost:8080/api/v1/events/evt_a1b2c3d4/availability
```

**Response 200:**
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

#### Reserve Tickets

The `X-Idempotency-Key` header is **required**. Use a UUID generated client-side. Retrying with the same key returns the cached response without creating a duplicate reservation.

```bash
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: $(uuidgen)" \
  -d '{
    "eventId": "evt_a1b2c3d4",
    "userId": "usr_test_001",
    "quantity": 2
  }'
```

**Response 201:**
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

**Idempotency test — same key returns cached response:**

```bash
KEY=$(uuidgen)

# First call — creates reservation
curl -X POST http://localhost:8080/api/v1/orders \
  -H "X-Idempotency-Key: $KEY" \
  -H "Content-Type: application/json" \
  -d '{"eventId":"evt_a1b2c3d4","userId":"usr_001","quantity":1}'

# Second call — returns same orderId, no duplicate created
curl -X POST http://localhost:8080/api/v1/orders \
  -H "X-Idempotency-Key: $KEY" \
  -H "Content-Type: application/json" \
  -d '{"eventId":"evt_a1b2c3d4","userId":"usr_001","quantity":1}'
```

#### Get Order Status

```bash
curl http://localhost:8080/api/v1/orders/ord_x1y2z3w4
```

**Response 200:**
```json
{
  "orderId": "ord_x1y2z3w4-...",
  "status": "CONFIRMED",
  "eventId": "evt_a1b2c3d4",
  "quantity": 2,
  "totalAmount": 700000,
  "currency": "COP"
}
```

---

### Error Responses

All errors follow a consistent structure:

```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Event not found: evt_unknown",
  "path": "/api/v1/events/evt_unknown",
  "timestamp": "2027-06-01T10:00:00Z"
}
```

| HTTP Status | Scenario |
|---|---|
| `400` | Missing `X-Idempotency-Key`, validation failure |
| `404` | Event or order not found |
| `409` | No tickets available |
| `500` | Internal server error (never exposes stack traces) |

---

### Actuator Endpoints

```bash
# Health check
curl http://localhost:8080/actuator/health

# Prometheus metrics
curl http://localhost:8080/actuator/prometheus

# Environment info
curl http://localhost:8080/actuator/env
```

---

## Key Design Decisions

### 1. Java 25 — requisito explícito de la prueba técnica

Java 25 es la versión exigida por la prueba. Todas las features utilizadas son GA (no preview):

- **Virtual Threads** — `spring.threads.virtual.enabled: true` + `Executors.newVirtualThreadPerTaskExecutor()` en tests de concurrencia. Los 100 threads concurrentes del test de overselling son Virtual Threads — sin overhead de OS threads.
- **Pattern Matching en switch** — `GlobalErrorHandler.resolveStatus()` mapea excepciones tipadas a HTTP status codes sin casting explícito.
- **Sealed interfaces** — `DomainEvent` con `permits TicketReserved, TicketSold, TicketReleased, OrderConfirmed, OrderFailed`. El compilador garantiza exhaustividad en switch expressions.
- **Records con constructores compactos** — `Money`, `EventId`, `TicketId`, todos los value objects del dominio con validación en el constructor compacto.

AWS SDK `2.20.26` está fijado en esa versión por compatibilidad con DynamoDB Local en desarrollo. En producción (AWS real) se puede usar la última versión.

### 2. `concatMap` (secuencial) sobre `flatMap` (concurrente) en reservas

Al reservar N tickets, `concatMap` permite compensación exacta si el ticket K falla. Con `flatMap` paralelo no es posible saber qué tickets completaron antes del fallo. El costo es latencia marginal para órdenes multi-ticket — inexistente para el caso más común (1 ticket). **Decisión: correctitud > velocidad en un sistema financiero.** Ver análisis completo en [`docs/TRADE_OFFS.md`](docs/TRADE_OFFS.md).

### 3. DynamoDB conditional writes — anti-overselling sin lock distribuido

Cada actualización de ticket incluye `ConditionExpression: version = N`. Bajo 100 requests concurrentes compitiendo por el mismo ticket, exactamente uno gana. Los demás reciben `409 Conflict`. Verificado con el test `TicketReservationConcurrencyIT` con infraestructura real (LocalStack).

### 4. Credenciales por `@Profile` — seguridad en producción

`DynamoDbConfig` y `SqsConfig` usan `@Profile({"local","test"})` para `StaticCredentialsProvider` (credenciales ficticias apuntando a LocalStack) y `@Profile({"prod","staging"})` para `DefaultCredentialsProvider` (resuelve automáticamente el Task IAM Role de ECS — cero credenciales hardcodeadas). Ver [`docs/SECURITY.md`](docs/SECURITY.md).

### 5. SQS para procesamiento asíncrono de órdenes

Desacopla la reserva (síncrona, respuesta inmediata HTTP 201) del procesamiento de pago (asíncrono, puede fallar y reintentar). El consumer es idempotente — detecta estado final `isFinal() == true` y hace no-op en reprocesamiento. Retry con backoff exponencial antes de dejar que SQS reencole al DLQ.

### 6. WebFlux + Virtual Threads

`WebFlux` con Netty libera el event loop en cada llamada I/O (DynamoDB, SQS). `Virtual Threads` eliminan el overhead de OS threads en el executor de completación del SDK. Combinados: alta concurrencia con footprint de memoria mínimo — crítico para picos de venta de entradas.

### 7. ShedLock para scheduler distribuido

Con múltiples instancias ECS, un `@Scheduled` plano ejecuta el job de expiración en todas las instancias simultáneamente — liberando los mismos tickets N veces. ShedLock usa DynamoDB como lock distribuido, garantizando que solo una instancia por ciclo ejecute el job.

### 8. Separación unit tests vs integration tests

- `*Test.java` / `*Tests.java` — unit tests con Mockito, sin Docker, corren en `./mvnw test` (< 10s).
- `*IT.java` — integration tests con Testcontainers/LocalStack, corren en `./mvnw verify` (requieren Docker).

Esta separación permite que los PRs en CI validen la lógica de negocio rápidamente sin necesidad de Docker, y que el gate de integración completo corra en el pipeline de merge.

---

## Project Structure

```
src/main/java/com/nequi/ticketing/
│
├── domain/                         # Zero external dependencies
│   ├── model/                      # Event, Ticket, Order, TicketStatus, OrderStatus
│   ├── valueobject/                # EventId, TicketId, OrderId, Money, Venue
│   ├── repository/                 # Reactive repository interfaces (output ports)
│   ├── service/                    # TicketStateMachine
│   └── exception/                  # Domain exceptions
│
├── application/                    # Orchestrates domain
│   ├── usecase/                    # CreateEvent, ReserveTickets, ProcessOrder...
│   ├── port/
│   │   ├── in/                     # Input ports (driving)
│   │   └── out/                    # Output ports (driven)
│   └── dto/                        # Request/Response DTOs
│
└── infrastructure/                 # Implements ports
    ├── config/                     # AWS, Jackson, ShedLock, CORS, TicketingProperties
    ├── persistence/dynamodb/       # Entities, mappers, DynamoDB repositories
    ├── messaging/sqs/              # SqsMessagePublisher, SqsOrderConsumer
    ├── scheduler/                  # ExpiredReservationScheduler (@SchedulerLock)
    ├── web/
    │   ├── filter/                 # CorrelationIdFilter (MDC propagation)
    │   ├── handler/                # EventHandler, OrderHandler, AvailabilityHandler
    │   └── router/                 # Functional routing (EventRouter, OrderRouter)
    └── shared/
        └── error/                  # GlobalErrorHandler
```

---

## Infrastructure

Terraform modules for production AWS deployment:

```
terraform/
├── modules/
│   ├── networking/     VPC multi-AZ, private subnets, NAT, VPC endpoints
│   ├── dynamodb/       6 tables, GSIs, TTL, PITR, encryption at rest
│   ├── sqs/            Queue + DLQ, long polling, KMS, CloudWatch alarm
│   ├── ecs/            Fargate cluster, ALB HTTPS, rolling deploy, auto-scaling
│   └── iam/            Least-privilege roles (execution vs task)
└── environments/
    ├── prod.tfvars
    └── dev.tfvars
```

```bash
cd terraform
terraform init
terraform plan -var-file=environments/prod.tfvars
terraform apply -var-file=environments/prod.tfvars
```

See [terraform/TERRAFORM.md](terraform/TERRAFORM.md) for architecture decisions and cost estimates (~$102/month production).

---

## CI/CD

GitHub Actions pipeline — `.github/workflows/ci-cd.yml`:

```
Pull Request  →  Build & Test  +  Terraform Validate
Push main     →  above  +  Docker Build & Push  +  Terraform Plan
Tag v*        →  above  +  Deploy Production (manual approval required)
```

| Job | Trigger | Description |
|---|---|---|
| Build & Test | All | 65 tests + JaCoCo coverage gate |
| Terraform Validate | All | `terraform fmt -check` + `terraform validate` |
| Docker Build & Push | `main`, tags | Build JAR → Docker image → ECR |
| Terraform Plan | `main` | `terraform plan` against staging |
| Deploy Production | `v*` tags | `terraform apply` + ECS rolling update + smoke test |

---

## Documentation

| Document | Description |
|---|---|
| [docs/architecture/ARCHITECTURE.md](docs/architecture/ARCHITECTURE.md) | ASCII diagrams — Clean Architecture, sequence diagrams, state machine, DynamoDB model |
| [docs/decisions/SECURITY.md](docs/decisions/SECURITY.md) | IAM least privilege, idempotency attacks, input validation, error response hardening |
| [docs/decisions/TRADE_OFFS.md](docs/decisions/TRADE_OFFS.md) | Known limitations, production improvements, what would change with more time |
| [docs/decisions/CLOUD_NATIVE.md](docs/decisions/CLOUD_NATIVE.md) | VPC design, cost estimates, observability three pillars, DR strategy |
| [terraform/TERRAFORM.md](terraform/TERRAFORM.md) | IaC decisions, commands, cost breakdown |
| [postman/](postman/) | Postman collection with automated assertions |
| [api-requests.sh](api-requests.sh) | Full curl test suite with idempotency verification |
