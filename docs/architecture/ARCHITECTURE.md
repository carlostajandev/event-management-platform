# Architecture Documentation

## 1. Diagrama de arquitectura — Vista de alto nivel

```
┌──────────────────────────────────────────────────────────────────────────┐
│                          AWS Cloud (us-east-1)                           │
│                                                                          │
│  ┌─────────────┐    ┌─────────────┐    ┌──────────────────────────────┐ │
│  │   Internet  │───►│     ALB     │───►│       ECS Fargate            │ │
│  │   Clients   │    │  (HTTPS)    │    │                              │ │
│  └─────────────┘    └─────────────┘    │  ┌────────────────────────┐  │ │
│                                        │  │  event-management-app  │  │ │
│                                        │  │  Spring Boot 4 WebFlux │  │ │
│                                        │  │  Java 21 LTS           │  │ │
│                                        │  └───────────┬────────────┘  │ │
│                                        └──────────────┼───────────────┘ │
│                                                       │                  │
│                        ┌──────────────────────────────┼──────────┐      │
│                        │                              │          │      │
│                        ▼                              ▼          ▼      │
│               ┌────────────────┐            ┌──────────────┐  ┌──────┐ │
│               │    DynamoDB    │            │     SQS      │  │  CW  │ │
│               │                │            │              │  │ Logs │ │
│               │ emp-events     │            │ purchase-    │  └──────┘ │
│               │ emp-tickets    │            │ orders       │           │
│               │ emp-orders     │            │              │  ┌──────┐ │
│               │ emp-idempotency│            │ (+ DLQ)      │  │  CW  │ │
│               │ emp-audit      │            └──────────────┘  │Alarm │ │
│               └────────────────┘                              └──────┘ │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  AWS Secrets Manager — credenciales, config sensible             │   │
│  └──────────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Clean Architecture — Capas y dependencias

```
┌─────────────────────────────────────────────────────────────────┐
│                      INFRASTRUCTURE                              │
│  ┌───────────────┐  ┌──────────────┐  ┌─────────────────────┐  │
│  │  Web Layer    │  │  Persistence │  │     Messaging       │  │
│  │  Routers      │  │  DynamoDB    │  │  SqsPublisher       │  │
│  │  Handlers     │  │  Entities    │  │  SqsConsumer        │  │
│  │  GlobalError  │  │  Mappers     │  │  Scheduler          │  │
│  └───────┬───────┘  └──────┬───────┘  └──────────┬──────────┘  │
│          │                 │                      │             │
└──────────┼─────────────────┼──────────────────────┼────────────┘
           │                 │                      │
           ▼                 ▼                      ▼
┌─────────────────────────────────────────────────────────────────┐
│                       APPLICATION                                │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  Use Cases (Services)                                    │   │
│  │  CreateEventService    ReserveTicketsService             │   │
│  │  ProcessOrderService   ReleaseExpiredReservationsService │   │
│  │  QueryOrderStatusService  CreatePurchaseOrderService     │   │
│  └──────────────────────┬───────────────────────────────────┘   │
│                         │                                        │
│  Ports In (interfaces)  │  Ports Out (interfaces)               │
│  ──────────────────────►│◄──────────────────────                │
└─────────────────────────┼────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────────┐
│                         DOMAIN                                   │
│  ┌──────────────┐  ┌─────────────────┐  ┌───────────────────┐  │
│  │    Models    │  │  Value Objects  │  │    Exceptions     │  │
│  │  Event       │  │  EventId        │  │  EventNotFound    │  │
│  │  Ticket      │  │  TicketId       │  │  TicketNotAvail.  │  │
│  │  Order       │  │  OrderId        │  │  InvalidState     │  │
│  │  TicketStatus│  │  Money          │  │  OrderNotFound    │  │
│  │  OrderStatus │  │  Venue          │  └───────────────────┘  │
│  └──────────────┘  └─────────────────┘                         │
│                                                                  │
│  ┌──────────────────────┐  ┌───────────────────────────────┐   │
│  │  Repository Ports    │  │  Domain Services              │   │
│  │  EventRepository     │  │  TicketStateMachine           │   │
│  │  TicketRepository    │  └───────────────────────────────┘   │
│  │  OrderRepository     │                                       │
│  │  IdempotencyRepo     │                                       │
│  └──────────────────────┘                                       │
└─────────────────────────────────────────────────────────────────┘

REGLA: Las flechas apuntan hacia adentro. El dominio no importa nada externo.
```

---

## 3. Diagrama de secuencia — Reserva de tickets

```
Cliente     API (WebFlux)    IdempotencyRepo    EventRepo    TicketRepo    SQS
  │               │                │               │             │          │
  │─POST /orders─►│                │               │             │          │
  │  X-Idem-Key   │                │               │             │          │
  │               │─exists(key)?──►│               │             │          │
  │               │◄──false────────│               │             │          │
  │               │                │               │             │          │
  │               │─findById(evt)──────────────────►│             │          │
  │               │◄──Event────────────────────────│             │          │
  │               │                │               │             │          │
  │               │─findAvailable(N)────────────────────────────►│          │
  │               │◄──[ticket1, ticket2]────────────────────────│          │
  │               │                │               │             │          │
  │               │─update(reserved)────────────────────────────►│          │
  │               │  [version=N+1  │               │             │          │
  │               │   condition]   │               │             │          │
  │               │◄──OK───────────────────────────────────────-│          │
  │               │                │               │             │          │
  │               │─save(key,resp)─►│               │             │          │
  │               │◄──OK───────────│               │             │          │
  │               │                │               │             │          │
  │◄──201 RESERVED│                │               │             │          │
  │   orderId     │                │               │             │          │
  │   expiresAt   │                │               │             │          │
```

---

## 4. Diagrama de secuencia — Procesamiento asíncrono de orden

```
SqsConsumer    OrderRepo    TicketRepo    SQS
    │               │            │          │
    │─poll()────────────────────────────────►│
    │◄──[message]───────────────────────────│
    │               │            │          │
    │─findById(ord)─►│            │          │
    │◄──Order(PENDING)│           │          │
    │               │            │          │
    │─update(PROCESSING)►│       │          │
    │◄──OK───────────│            │          │
    │               │            │          │
    │─findById(tkt1)──────────────►│         │
    │◄──Ticket(RESERVED)──────────│         │
    │─update(PENDING_CONF)────────►│         │
    │─update(SOLD)────────────────►│         │
    │               │            │          │
    │─update(CONFIRMED)──►│       │          │
    │◄──OK───────────│            │          │
    │               │            │          │
    │─deleteMessage()───────────────────────►│
    │◄──OK──────────────────────────────────│
```

---

## 5. Diagrama de estados — Ticket lifecycle

```
                    ┌─────────────────────────┐
                    │                         │
              ┌─────▼──────┐           ┌──────▼──────────┐
              │  AVAILABLE │           │  COMPLIMENTARY  │
              └─────┬──────┘           │    (FINAL)      │
                    │                  └─────────────────┘
                    │ reserve(userId, orderId, TTL)
                    ▼
              ┌─────────────┐
              │  RESERVED   │──── expiresAt < now ────►┐
              └─────┬───────┘                          │
                    │ confirmPending()                  │
                    ▼                                   │
        ┌───────────────────────┐                      │
        │  PENDING_CONFIRMATION │                      │
        └─────────┬─────────────┘                      │
                  │                                     │
          ┌───────┴───────┐                            │
          │               │                            │
          ▼               ▼                            ▼
    ┌──────────┐   ┌───────────┐              ┌──────────────┐
    │  SOLD    │   │ AVAILABLE │◄─────────────│  AVAILABLE   │
    │ (FINAL)  │   │(payment   │  release()   │  (expiry     │
    └──────────┘   │ failed)   │              │   released)  │
                   └───────────┘              └──────────────┘
```

---

## 6. Modelo de datos DynamoDB

### Tabla: `emp-tickets`
| PK | GSI PK | GSI SK | Atributos |
|---|---|---|---|
| `ticketId` | `eventId` | `status` | userId, orderId, price, currency, reservedAt, expiresAt, confirmedAt, version |

**GSI**: `eventId-status-index` → permite queries eficientes "dame todos los AVAILABLE del evento X"

### Tabla: `emp-orders`
| PK | GSI PK | Atributos |
|---|---|---|
| `orderId` | `userId` | eventId, ticketIds[], quantity, totalAmount, status, failureReason, version |

### Tabla: `emp-idempotency`
| PK | Atributos | TTL |
|---|---|---|
| `idempotencyKey` | responseJson, createdAt | `expiresAt` (Unix epoch — DynamoDB TTL nativo) |

---

## 7. Interacciones entre componentes

```
┌──────────┐     ┌──────────────────┐     ┌──────────────────┐
│ HTTP     │     │  Application     │     │  Infrastructure  │
│ Layer    │     │  Layer           │     │  Layer           │
│          │     │                  │     │                  │
│ Router   │────►│ ReserveTickets   │────►│ TicketDynamo     │
│ Handler  │     │ Service          │     │ Repository       │
│          │     │                  │     │                  │
│          │     │ ┌──────────────┐ │     │ IdempotencyDynamo│
│          │     │ │TicketState   │ │     │ Repository       │
│          │     │ │Machine       │ │     │                  │
│          │     │ └──────────────┘ │     │ SqsMessage       │
│          │     │                  │────►│ Publisher        │
└──────────┘     └──────────────────┘     └──────────────────┘
                                                   │
                                          ┌────────▼─────────┐
                                          │    AWS Services   │
                                          │  DynamoDB Local   │
                                          │  LocalStack SQS   │
                                          └──────────────────┘
```

**Scheduler** (independiente del flujo HTTP):
```
ExpiredReservationScheduler ──every 60s──► ReleaseExpiredReservationsService
                                                      │
                                                      ▼
                                           TicketRepository.findExpiredReservations()
                                                      │
                                                      ▼
                                           ticket.release() → update(version+1)
```
