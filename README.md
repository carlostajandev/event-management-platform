# Event Management Platform

Plataforma backend reactiva para venta de entradas de alta concurrencia.
Diseñada para manejar picos de demanda donde miles de usuarios intentan
comprar simultáneamente (conciertos, deportes, teatro).

---

## Stack tecnológico

| Capa | Tecnología |
|---|---|
| Lenguaje | Java 21 (Virtual Threads, Records) |
| Framework | Spring Boot 4.0.3 + Spring WebFlux |
| Base de datos | AWS DynamoDB (DynamoDB Local en dev) |
| Mensajería | AWS SQS (LocalStack en dev) |
| Arquitectura | Clean Architecture + DDD |
| Contenedores | Docker + Docker Compose |
| Tests | JUnit 5, Mockito, StepVerifier, Testcontainers |
| Observabilidad | Micrometer, Prometheus, Logback JSON |

---

## Arquitectura

```
┌─────────────────────────────────────────────────────┐
│  domain/          Entidades, Value Objects,          │
│                   Interfaces de repositorio          │
│                   Sin dependencias externas          │
├─────────────────────────────────────────────────────┤
│  application/     Casos de uso (interactors)         │
│                   Puertos in/out, DTOs               │
│                   Depende solo de domain             │
├─────────────────────────────────────────────────────┤
│  infrastructure/  DynamoDB, SQS, HTTP, Scheduler     │
│                   Implementa puertos del dominio     │
├─────────────────────────────────────────────────────┤
│  shared/          Error handling, Logging, Utils     │
└─────────────────────────────────────────────────────┘
```

---

## Requisitos

- Java 21+
- Maven 3.9+
- Docker + Docker Compose v2

---

## Levantar el proyecto

### Solo infraestructura (desarrollo activo)

```bash
docker compose up -d
```

Inicia: DynamoDB Local `:8000` · DynamoDB Admin UI `:8001` · LocalStack/SQS `:4566`

### Aplicación + infraestructura

```bash
docker compose --profile app up
```

### Correr la app localmente (con infra en Docker)

```bash
docker compose up -d
./mvnw spring-boot:run
```

App disponible en `http://localhost:8080`

---

## Comandos útiles

```bash
# Compilar sin tests
./mvnw package -DskipTests

# Correr tests unitarios
./mvnw test

# Tests + cobertura (falla si < 90%)
./mvnw verify

# Ver reporte de cobertura
open target/site/jacoco/index.html

# Inspeccionar tablas DynamoDB
open http://localhost:8001

# Verificar colas SQS
aws --endpoint-url http://localhost:4566 sqs list-queues --region us-east-1
```

---

## Endpoints

| Método | Path | Descripción |
|---|---|---|
| `POST` | `/api/v1/events` | Crear evento |
| `GET` | `/api/v1/events` | Listar eventos |
| `GET` | `/api/v1/events/{eventId}` | Consultar evento |
| `GET` | `/api/v1/events/{eventId}/availability` | Disponibilidad en tiempo real |
| `POST` | `/api/v1/orders` | Crear orden de compra (async) |
| `GET` | `/api/v1/orders/{orderId}` | Consultar estado de orden |
| `GET` | `/actuator/health` | Health check |
| `GET` | `/actuator/prometheus` | Métricas Prometheus |

---

## Estados de una entrada

```
AVAILABLE → RESERVED (reserva temporal 10 min)
             ↓
         PENDING_CONFIRMATION (consumidor SQS procesando)
             ↓               ↓
           SOLD          AVAILABLE (pago fallido → libera)

AVAILABLE → COMPLIMENTARY (cortesía, final, no contable)
```

---

## Variables de entorno

| Variable | Default local | Descripción |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | Perfil activo |
| `SERVER_PORT` | `8080` | Puerto HTTP |
| `DYNAMODB_ENDPOINT` | `http://localhost:8000` | Endpoint DynamoDB |
| `SQS_ENDPOINT` | `http://localhost:4566` | Endpoint SQS |
| `AWS_REGION` | `us-east-1` | Región AWS |
| `RESERVATION_TTL_MINUTES` | `10` | TTL reserva temporal |
| `MAX_TICKETS_PER_ORDER` | `10` | Máximo tickets por orden |
| `RATE_LIMIT_PER_SECOND` | `100` | Rate limit API compra |
| `LOG_LEVEL_APP` | `DEBUG` | Nivel de log aplicación |

---

## Estrategia de concurrencia

- **Optimistic locking**: `ConditionExpression` en DynamoDB con `version + status`. Solo una request gana; las demás reciben `409 Conflict` y reintentan con backoff.
- **Idempotency keys**: header `X-Idempotency-Key` previene duplicados en reintentos de red. Clave almacenada en DynamoDB con TTL 24h.
- **At-least-once delivery**: SQS con 3 reintentos + Dead Letter Queue.
- **Liberación automática**: scheduler corre cada 60s y libera reservas con TTL vencido.

---

## Git branches

Ver [docs/decisions/git-branching.md](docs/decisions/git-branching.md)
