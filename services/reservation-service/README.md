# reservation-service

Handles ticket reservation lifecycle. Owns `emp-reservations` and `emp-audit` DynamoDB tables.

**Port:** 8082

## Responsibilities

- Reserve tickets for a user against an event (atomic, no overselling)
- Enforce 10-minute reservation expiry via DynamoDB TTL
- Release inventory when reservation expires (TTL → DynamoDB Streams → Lambda in prod; GSI-based scheduler fallback in local/dev)
- Record all state transitions in `emp-audit`

## Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/reservations` | Reserve tickets (requires `X-Correlation-Id` header) |
| `GET` | `/api/v1/reservations/{reservationId}` | Get reservation by ID |
| `GET` | `/api/v1/reservations/user/{userId}` | Get active reservations for a user |
| `GET` | `/actuator/health` | Health check |
| `GET` | `/actuator/prometheus` | Prometheus metrics |

### POST /api/v1/reservations

**Request:**
```json
{
  "eventId": "evt_123",
  "userId": "u_789",
  "seatsCount": 2
}
```
- `seatsCount`: 1–10 (Bean Validation `@Min(1) @Max(10)` — prevents inventory exhaustion attack)

**Response 201:**
```json
{
  "reservationId": "res_abc",
  "eventId": "evt_123",
  "userId": "u_789",
  "seatsCount": 2,
  "totalAmount": 150000.00,
  "currency": "COP",
  "status": "ACTIVE",
  "expiresAt": "2026-03-16T10:10:00Z"
}
```

**Error responses:**
- `409 Conflict` — no tickets available or concurrent version conflict (after 3 retries)
- `400 Bad Request` — validation failure

## Key Design Decisions

- **Conditional write**: `UpdateItem` on `emp-events` with `ConditionExpression: availableCount >= :seatsCount AND version = :expected`. Atomic, no SPOF.
- **Retry**: `Mono.defer(() -> eventRepository.reserveTickets(...)).retryWhen(Retry.backoff(3, Duration.ofMillis(100)).filter(e -> e instanceof ConcurrentModificationException))`. `Mono.defer` is required so each retry re-executes the repository call.
- **TTL expiry**: `expiresAt = now + 600s` stored as epoch seconds. DynamoDB auto-deletes expired items. No @Scheduled full table scan.
- **Expiry reconciliation**: GSI query `STATUS#ACTIVE AND expiresAt <= now` — O(results), not O(table). Used by local scheduler fallback only.

## DynamoDB Tables

### emp-reservations

| Key | Pattern |
|---|---|
| PK | `RESERVATION#{reservationId}` |
| SK | `METADATA` |
| GSI1PK | `STATUS#{status}` |
| GSI1SK | `EXPIRES#{expiresAt.epoch}` |
| TTL | `ttl` (epoch seconds) |

### emp-audit

| Key | Pattern |
|---|---|
| PK | `AUDIT#{aggregateId}` |
| SK | `EVENT#{occurredAt.iso}` |
| TTL | `ttl` (90 days) |

## Running Locally

```bash
docker compose -f infrastructure/docker-compose.yml up localstack -d
./mvnw spring-boot:run -pl services/reservation-service -Dspring-boot.run.profiles=local
```

## Running Tests

```bash
./mvnw test -pl shared/domain,shared/infrastructure,services/reservation-service
```
