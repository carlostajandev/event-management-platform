<div align="center">

# Event Management Platform

**Plataforma reactiva de ticketing de alta concurrencia — Prueba Técnica Nequi**

[![CI/CD](https://github.com/carlostajandev/event-management-platform/actions/workflows/ci-cd.yml/badge.svg)](https://github.com/carlostajandev/event-management-platform/actions)
![Java](https://img.shields.io/badge/Java-21%20LTS-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.3-green)
![Tests](https://img.shields.io/badge/tests-65%20passing-brightgreen)
![Coverage](https://img.shields.io/badge/coverage-%3E90%25-brightgreen)
![Architecture](https://img.shields.io/badge/arquitectura-Clean%20Architecture-blue)
![IaC](https://img.shields.io/badge/IaC-Terraform-purple)

</div>

---

## Descripción General

Plataforma de ticketing reactiva lista para producción, diseñada para manejar venta de entradas de conciertos a escala — construida para alta concurrencia, consistencia financiera y observabilidad operacional.

**Garantías del sistema:**
- **Sin overselling** — optimistic locking con conditional writes de DynamoDB
- **Sin cargos duplicados** — idempotency keys con TTL de 24h
- **Sin pérdida de datos** — entrega at-least-once de SQS con consumidor idempotente
- **Sin punto único de falla** — ECS Fargate multi-AZ + DynamoDB Global Tables

---

## Tabla de Contenido

- [Arquitectura](#arquitectura)
- [Stack Tecnológico](#stack-tecnológico)
- [Prerrequisitos](#prerrequisitos)
- [Configuración Local](#configuración-local)
- [Ejecutar con Docker](#ejecutar-con-docker)
- [Ejecutar Tests](#ejecutar-tests)
- [Referencia de la API](#referencia-de-la-api)
- [Decisiones de Diseño](#decisiones-de-diseño)
- [Estructura del Proyecto](#estructura-del-proyecto)
- [Infraestructura (Terraform)](#infraestructura-terraform)
- [Pipeline CI/CD](#pipeline-cicd)
- [Documentación](#documentación)

---

## Arquitectura

### Clean Architecture — Reglas de Dependencia

```
┌──────────────────────────────────────────────────────────────────────┐
│                   Capa HTTP (WebFlux / Netty)                         │
│              Routers Funcionales + Handlers + Filtros                 │
├──────────────────────────────────────────────────────────────────────┤
│                        INFRAESTRUCTURA                                │
│                                                                       │
│  ┌──────────────┐  ┌─────────────────────┐  ┌─────────────────────┐ │
│  │  Capa Web    │  │    Persistencia     │  │     Mensajería      │ │
│  │  Handlers    │  │    DynamoDB         │  │  SQS Publisher      │ │
│  │  Routers     │  │    Mappers          │  │  SQS Consumer       │ │
│  │  Filtros     │  │    Entities         │  │  Scheduler          │ │
│  └──────┬───────┘  └──────────┬──────────┘  └──────────┬──────────┘ │
└─────────┼────────────────────┼────────────────────────┼─────────────┘
          │ port/in            │ port/out               │ port/out
          ▼                    ▼                        ▼
┌──────────────────────────────────────────────────────────────────────┐
│                          APLICACIÓN                                   │
│                                                                       │
│  CreateEventService          ReserveTicketsService                    │
│  GetEventService             ProcessOrderService                      │
│  CreatePurchaseOrderService  ReleaseExpiredReservationsService        │
│  QueryOrderStatusService     AuditService                             │
│                                                                       │
│  REGLA: Los casos de uso solo dependen de interfaces del dominio      │
└─────────────────────────────┬────────────────────────────────────────┘
                              │ solo interfaces del dominio
                              ▼
┌──────────────────────────────────────────────────────────────────────┐
│                            DOMINIO                                    │
│                  (cero dependencias externas)                         │
│                                                                       │
│  Modelos:         Event, Ticket, Order, TicketStatus, OrderStatus     │
│  Value Objects:   EventId, TicketId, OrderId, Money, Venue            │
│  Servicios:       TicketStateMachine                                  │
│  Excepciones:     EventNotFound, TicketNotAvailable, OrderNotFound    │
│  Repositorios:    Interfaces reactivas (puertos de salida)            │
└──────────────────────────────────────────────────────────────────────┘

REGLA: Las flechas apuntan hacia adentro. El dominio NO importa Spring, AWS ni ningún framework.
```

### Flujo de Reserva de Tickets

```
Cliente         API (WebFlux)       Idempotency     Evento      Ticket      SQS
  │                  │                  Repo          Repo        Repo        │
  │─POST /orders────►│                  │              │           │          │
  │  X-Idem-Key      │                  │              │           │          │
  │                  │─exists(key)?────►│              │           │          │
  │                  │◄─false───────────│              │           │          │
  │                  │─findById(evt)───────────────► │            │          │
  │                  │◄─Evento──────────────────────  │            │          │
  │                  │─findAvailable(N)─────────────────────────►  │          │
  │                  │◄─[tkt_1, tkt_2]──────────────────────────   │          │
  │                  │─update(version=N+1)──────────────────────►  │          │
  │                  │  ConditionExpression            │            │          │
  │                  │◄─OK──────────────────────────────────────   │          │
  │                  │─save(key, resp)─►│              │            │          │
  │◄─201 RESERVED────│                  │              │            │          │
  │                  │─publish(orden)──────────────────────────────────────►  │
  │                  │                  │              │            │   async  │
  │                  │         SqsOrderConsumer hace poll cada 5s             │
  │                  │         RESERVED → PENDING_CONFIRMATION → SOLD         │
```

### Máquina de Estados de Tickets

```
                    ┌────────────────────────────┐
                    │                            │
              ┌─────▼──────┐            ┌────────▼────────┐
              │ AVAILABLE  │            │  COMPLIMENTARY  │
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
     │  (FINAL) │    │ (fallido) │
     └──────────┘    └───────────┘
```

### Arquitectura AWS en Producción

```
                        ┌─────────────────────────────────────┐
                        │         Route 53 (DNS)               │
                        │   api.ticketing.nequi.com            │
                        └──────────────────┬──────────────────┘
                                           │
                        ┌──────────────────▼──────────────────┐
                        │    CloudFront + WAF                  │
                        │  Rate limiting · Reglas OWASP        │
                        └──────────────────┬──────────────────┘
                                           │
                        ┌──────────────────▼──────────────────┐
                        │  Application Load Balancer (ALB)    │
                        │  Terminación TLS · Certificado ACM  │
                        │  Redirect HTTP → HTTPS              │
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
         │  (6 tablas)         │     │  purchase-orders     │
         │  PITR habilitado    │     │  + DLQ               │
         │  Cifrado en reposo  │     │  Cifrado KMS         │
         └─────────────────────┘     └──────────────────────┘
```

---

## Stack Tecnológico

| Componente | Tecnología | Decisión |
|---|---|---|
| **Runtime** | Java 21 LTS | Elegido sobre Java 25 EA — AWS SDK v2, Resilience4j y Testcontainers tienen soporte certificado. Virtual Threads en release final. Java 25 está en Early Access — no apto para sistemas financieros en producción. |
| **Framework** | Spring Boot 4.0.3 + WebFlux | I/O non-blocking sobre Netty. Cada llamada a DynamoDB/SQS libera el hilo inmediatamente — maneja muchas más conexiones concurrentes que MVC con el mismo hardware. |
| **Serialización** | Jackson 3 (`tools.jackson`) | Spring Boot 4 migró de Jackson 2. `JsonMapper` (concreto) inyectado directamente — elimina ambigüedad de beans de Spring al resolver `ObjectMapper` (abstracto). |
| **Base de datos** | DynamoDB (PAY_PER_REQUEST) | Latencia P99 predecible. TTL nativo para expiración de idempotency keys (sin Lambda de limpieza). GSI para queries eficientes. PITR para recuperación de datos financieros. |
| **Mensajería** | SQS Standard | Entrega at-least-once. DLQ tras 3 fallos. Long polling (20s) reduce receives vacíos ~95%. Desacopla reserva (síncrona) del procesamiento de pago (asíncrono, con reintentos). |
| **Resiliencia** | Resilience4j 2.3.0 | `@CircuitBreaker` en todos los repositorios DynamoDB y SQS publisher. `@Retry` con backoff exponencial en SQS. Previene fallos en cascada. |
| **Observabilidad** | Micrometer + Prometheus | Métricas en `/actuator/prometheus`. `X-Correlation-Id` propagado por `CorrelationIdFilter` al MDC — cada log incluye el trace ID. |
| **Lock Distribuido** | ShedLock 6.0.1 + DynamoDB | Previene que N instancias ECS ejecuten el scheduler de expiración N veces. `lockAtMostFor=55s` libera el lock incluso si la instancia crashea. |
| **IaC** | Terraform 1.7+ | 5 módulos: `networking`, `dynamodb`, `sqs`, `ecs`, `iam`. IAM mínimo privilegio — execution role y task role separados. |

---

## Prerrequisitos

| Herramienta | Versión | Instalación |
|---|---|---|
| Java | 21 LTS | [Temurin](https://adoptium.net/) |
| Docker | 20.10+ | [Docker Desktop](https://www.docker.com/products/docker-desktop/) |
| Docker Compose | 2.0+ | Incluido con Docker Desktop |
| Maven | 3.9+ | O usar el wrapper `./mvnw` (incluido) |

No se necesita cuenta AWS para desarrollo local.

---

## Configuración Local

### 1. Clonar el repositorio

```bash
git clone https://github.com/carlostajandev/event-management-platform.git
cd event-management-platform
```

### 2. Levantar infraestructura local

```bash
docker compose up -d
```

Esto inicia:
- **DynamoDB Local 1.25.0** en `http://localhost:8000`
- **LocalStack 3.6** (SQS) en `http://localhost:4566`

Verificar que están healthy:

```bash
docker compose ps
# NOMBRE             ESTADO
# dynamodb-local     Up (healthy)
# localstack         Up (healthy)
```

### 3. Iniciar la aplicación

```bash
./mvnw spring-boot:run
```

Las tablas DynamoDB se crean automáticamente al arrancar:

```
✓ emp-events
✓ emp-tickets       (GSI: eventId-status-index)
✓ emp-orders        (GSI: userId-index)
✓ emp-idempotency   (TTL: expiresAt)
✓ emp-audit         (PK: entityId, SK: timestamp)
```

### 4. Verificar

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

### Configuración del entorno

`src/main/resources/application-local.yml` — no se necesitan credenciales AWS reales:

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

## Ejecutar con Docker

```bash
# Levantar stack completo (infraestructura + aplicación)
docker compose up -d

# Ver logs de la aplicación en tiempo real
docker compose logs -f app

# Detener todo
docker compose down

# Detener y limpiar volúmenes
docker compose down -v
```

---

## Ejecutar Tests

```bash
# Suite completa con reporte JaCoCo
./mvnw test

# Verificar con gate de cobertura (falla si está por debajo del umbral)
./mvnw verify

# Test específico
./mvnw test -Dtest="TicketStateMachineTest"

# Múltiples tests
./mvnw test -Dtest="TicketStateMachineTest,ReserveTicketsServiceTest,AuditServiceTest"
```

### Resultados — 65 tests, 0 fallos

| Suite de Tests | Tests | Foco de Cobertura |
|---|---|---|
| `EventDomainTest` | 8 | Validaciones del modelo Event, reglas de negocio |
| `TicketDomainTest` | 7 | Modelo Ticket, transiciones de estado |
| `TicketStateMachineTest` | 17 | Todas las transiciones válidas e inválidas |
| `CreateEventServiceTest` | 3 | Creación de eventos, validación |
| `ReserveTicketsServiceTest` | 5 | Reserva, idempotencia, modificación concurrente |
| `ProcessOrderServiceTest` | 4 | Procesamiento de órdenes, consumidor idempotente |
| `ReleaseExpiredReservationsServiceTest` | 3 | Lógica del scheduler de expiración |
| `CreatePurchaseOrderServiceTest` | 2 | Creación de orden + publicación en SQS |
| `AuditServiceTest` | 4 | Audit trail, absorción de fallos |
| `CorrelationIdFilterTest` | 2 | Filtro HTTP, propagación MDC |
| `EventHandlerTest` | 5 | Capa HTTP, slice `@WebFluxTest` |
| `OrderHandlerTest` | 4 | Capa HTTP, validación header idempotencia |
| `EventManagementPlatformApplicationTests` | 1 | Carga completa del contexto Spring |

---

## Referencia de la API

URL Base: `http://localhost:8080`

### Eventos

#### `POST /api/v1/events` — Crear Evento

```bash
curl -s -X POST http://localhost:8080/api/v1/events \
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
  }' | jq '.'
```

**Respuesta `201 Created`:**
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

#### `GET /api/v1/events/{eventId}` — Obtener Evento

```bash
curl -s http://localhost:8080/api/v1/events/evt_a1b2c3d4 | jq '.'
```

---

#### `GET /api/v1/events` — Listar Eventos (paginado)

```bash
curl -s "http://localhost:8080/api/v1/events?page=0&size=10" | jq '.'
```

**Respuesta `200 OK`:**
```json
{
  "items": [{ "eventId": "...", "name": "...", "status": "DRAFT" }],
  "page": 0,
  "size": 10,
  "hasMore": false
}
```

---

#### `GET /api/v1/events/{eventId}/availability` — Disponibilidad en Tiempo Real

```bash
curl -s http://localhost:8080/api/v1/events/evt_a1b2c3d4/availability | jq '.'
```

**Respuesta `200 OK`:**
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

### Órdenes

#### `POST /api/v1/orders` — Reservar Tickets

> **Header requerido:** `X-Idempotency-Key` — un UUID generado por el cliente.
> Reintentar con la **misma key** devuelve la respuesta cacheada. No se crea una reserva duplicada.

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

**Respuesta `201 Created`:**
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

**Respuesta `400`** — falta idempotency key:
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "X-Idempotency-Key header is required"
}
```

**Respuesta `409`** — sin tickets disponibles:
```json
{
  "status": 409,
  "error": "Conflict",
  "message": "No available tickets for event: evt_a1b2c3d4"
}
```

---

#### `GET /api/v1/orders/{orderId}` — Estado de Orden

```bash
curl -s http://localhost:8080/api/v1/orders/ord_x1y2z3w4 | jq '.'
```

**Respuesta `200 OK`:**
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

# Métricas Prometheus
curl -s http://localhost:8080/actuator/prometheus | grep http_server

# Entorno activo
curl -s http://localhost:8080/actuator/env | jq '.activeProfiles'
```

---

### Referencia de Errores

| Estado HTTP | Escenario |
|---|---|
| `400` | Falta `X-Idempotency-Key`, validación fallida, parámetros de paginación inválidos |
| `404` | Evento u orden no encontrado |
| `409` | Sin tickets disponibles para la cantidad solicitada |
| `500` | Error interno — nunca expone stack traces, IPs internas ni nombres de tablas |

---

## Decisiones de Diseño

### 1. Java 21 LTS — no Java 25 Early Access

Java 25 está en Early Access. AWS SDK v2, Resilience4j y Testcontainers no tienen soporte certificado contra él. En un sistema de ticketing que procesa transacciones financieras bajo alta concurrencia, la estabilidad no es negociable. Java 21 LTS provee Virtual Threads (release final), Records, Pattern Matching y Sealed Classes.

**En revisión técnica:** *"Elegí Java 21 LTS conscientemente. Java 25 está en Early Access — el ecosistema incluyendo AWS SDK v2, Resilience4j y Testcontainers aún no tiene soporte certificado. Para un sistema que procesa transacciones financieras bajo alta concurrencia, la estabilidad no es negociable."*

### 2. Jackson 3 — `JsonMapper` (concreto) sobre `ObjectMapper` (abstracto)

Spring Boot 4 migró de Jackson 2 (`com.fasterxml.jackson`) a Jackson 3 (`tools.jackson`). El framework registra `JsonMapper` como bean — no `ObjectMapper`. Inyectar el tipo concreto directamente es más explícito, type-safe, y elimina la `NoUniqueBeanDefinitionException` que ocurre cuando Spring resuelve el `ObjectMapper` abstracto y encuentra múltiples candidatos.

### 3. DynamoDB como almacén único

Un solo almacén para tickets, órdenes, idempotency keys, audit log y ShedLock. DynamoDB provee: TTL nativo (las idempotency keys expiran automáticamente — sin Lambda de limpieza), latencia sub-milisegundo en P99, auto-scaling sin particionamiento manual, y PITR para recuperación de 35 días de datos financieros.

### 4. Optimistic Locking — sin lock distribuido

Cada actualización de ticket incluye `ConditionExpression: version = N`. Si dos requests concurrentes compiten por el mismo ticket, solo uno gana — DynamoDB rechaza el segundo con `ConditionalCheckFailedException`, mapeado a `409 Conflict`. Sin Redis, sin lock pesimista, sin overhead de coordinación. Escala horizontalmente sin contención.

### 5. SQS para procesamiento asíncrono de órdenes

Desacopla la reserva (síncrona, sub-100ms) del procesamiento del pago (asíncrono, con reintentos). El consumidor es completamente idempotente — si la misma orden se procesa dos veces, el segundo intento detecta el estado final y omite silenciosamente. SQS reintenta automáticamente hasta 3 veces antes de mover a DLQ.

### 6. WebFlux sobre Spring MVC

Cada llamada a DynamoDB o SQS libera el hilo inmediatamente vía Project Reactor. Con el mismo hardware, WebFlux maneja muchas más conexiones concurrentes que MVC thread-per-request. Crítico para picos de venta donde miles de usuarios hacen `POST /orders` simultáneamente.

### 7. ShedLock para scheduler distribuido

`@Scheduled` sin coordinación ejecutaría el job de expiración en cada instancia ECS simultáneamente — liberando los mismos tickets expirados múltiples veces, causando race conditions. ShedLock usa DynamoDB como lock distribuido (`lockAtMostFor=55s`, `lockAtLeastFor=30s`) — solo una instancia corre por ciclo, incluso durante rolling deployments.

---

## Estructura del Proyecto

```
event-management-platform/
│
├── src/main/java/com/nequi/ticketing/
│   │
│   ├── domain/                         # INTERNO — cero dependencias externas
│   │   ├── model/                      # Event, Ticket, Order, TicketStatus, OrderStatus
│   │   ├── valueobject/                # EventId, TicketId, OrderId, Money, Venue
│   │   ├── repository/                 # Interfaces reactivas (puertos de salida)
│   │   ├── service/                    # TicketStateMachine
│   │   └── exception/                  # EventNotFound, TicketNotAvailable, OrderNotFound
│   │
│   ├── application/                    # MEDIO — solo depende del dominio
│   │   ├── usecase/                    # Una clase por caso de uso
│   │   ├── port/in/                    # Puertos de entrada (driving interfaces)
│   │   ├── port/out/                   # Puertos de salida (driven interfaces)
│   │   └── dto/                        # DTOs de Request/Response
│   │
│   └── infrastructure/                 # EXTERNO — implementa los puertos
│       ├── config/                     # DynamoDbConfig, SqsConfig, ShedLockConfig, CorsConfig
│       ├── persistence/dynamodb/       # Entities, Mappers, repositorios DynamoDB
│       ├── messaging/sqs/              # SqsMessagePublisher, SqsOrderConsumer
│       ├── scheduler/                  # ExpiredReservationScheduler (@SchedulerLock)
│       ├── web/
│       │   ├── filter/                 # CorrelationIdFilter
│       │   ├── handler/                # EventHandler, OrderHandler, AvailabilityHandler
│       │   └── router/                 # EventRouter, OrderRouter (routing funcional)
│       └── shared/error/               # GlobalErrorHandler
│
├── terraform/                          # Infraestructura como Código
│   ├── modules/networking/             # VPC, subnets, NAT, VPC endpoints, security groups
│   ├── modules/dynamodb/               # 6 tablas, GSIs, TTL, PITR, cifrado
│   ├── modules/sqs/                    # Queue + DLQ + alarma CloudWatch
│   ├── modules/ecs/                    # Fargate, ALB, auto-scaling
│   ├── modules/iam/                    # Roles de mínimo privilegio
│   └── environments/                  # prod.tfvars, dev.tfvars
│
├── docs/
│   ├── architecture/ARCHITECTURE.md   # Diagramas, secuencias, máquina de estados
│   └── decisions/
│       ├── SECURITY.md                # IAM, ataques de idempotencia, validación
│       ├── TRADE_OFFS.md              # Limitaciones y mejoras para producción
│       └── CLOUD_NATIVE.md           # Diseño VPC, costos, DR, observabilidad
│
├── .github/workflows/ci-cd.yml        # Pipeline GitHub Actions
├── docker-compose.yml                 # DynamoDB Local + LocalStack
├── api-requests.sh                    # Suite completa de tests con curl
└── postman/                           # Colección Postman con assertions
```

---

## Infraestructura (Terraform)

5 módulos listos para producción en AWS:

| Módulo | Recursos | Decisiones Clave |
|---|---|---|
| `networking` | VPC, subnets privadas/públicas, NAT, VPC endpoints | Tráfico a DynamoDB/SQS/ECR se queda en AWS — sin costo NAT, sin internet público |
| `dynamodb` | 6 tablas, GSIs, TTL, PITR, cifrado | PAY_PER_REQUEST maneja picos sin capacity planning |
| `sqs` | Queue + DLQ + alarma CloudWatch | Alerta inmediata cuando hay mensajes en DLQ — fallos en procesamiento |
| `ecs` | Fargate, ALB HTTPS, rolling deploy 50/200%, auto-scaling | Scale out en 60s, scale in en 300s. Health check en `/actuator/health` |
| `iam` | Execution role + Task role | Sin wildcards en recursos. Acceso DynamoDB limitado a 6 tablas |

```bash
cd terraform
terraform init
terraform plan  -var-file=environments/prod.tfvars
terraform apply -var-file=environments/prod.tfvars
```

Ver [terraform/TERRAFORM.md](terraform/TERRAFORM.md) — decisiones, comandos, estimación de costos (~$102/mes producción).

---

## Pipeline CI/CD

`.github/workflows/ci-cd.yml` — GitHub Actions:

```
Pull Request → [Build & Test] + [Terraform Validate]
Push main    → lo anterior + [Docker Build & Push] + [Terraform Plan]
Tag v*       → lo anterior + [Deploy Producción] ← requiere aprobación manual
```

| Job | Trigger | Descripción |
|---|---|---|
| Build & Test | Todos | `./mvnw verify` — 65 tests + gate JaCoCo |
| Terraform Validate | Todos | `terraform fmt -check` + `terraform validate` (sin credenciales AWS) |
| Docker Build & Push | `main`, tags `v*` | JAR → imagen Docker → push a ECR |
| Terraform Plan | `main` | `terraform plan` contra entorno staging |
| Deploy Producción | Tags `v*` | `terraform apply` + ECS wait-stable + smoke test en `/actuator/health` |

---

## Documentación

| Documento | Descripción |
|---|---|
| [docs/architecture/ARCHITECTURE.md](docs/architecture/ARCHITECTURE.md) | Diagramas Clean Architecture, flujos de secuencia, máquina de estados, modelo DynamoDB |
| [docs/decisions/SECURITY.md](docs/decisions/SECURITY.md) | IAM mínimo privilegio, prevención de ataques de idempotencia, validación, hardening de errores |
| [docs/decisions/TRADE_OFFS.md](docs/decisions/TRADE_OFFS.md) | Limitaciones conocidas, qué cambiaría en producción, mejoras futuras |
| [docs/decisions/CLOUD_NATIVE.md](docs/decisions/CLOUD_NATIVE.md) | Diseño VPC, estimación de costos ($102/mes), tres pilares de observabilidad, estrategia DR |
| [terraform/TERRAFORM.md](terraform/TERRAFORM.md) | Decisiones IaC, comandos Terraform, desglose de costos |
| [api-requests.sh](api-requests.sh) | Suite completa curl — verificación de idempotencia, todos los escenarios de error |
| [postman/](postman/) | Colección Postman con assertions automatizadas para todos los endpoints |
