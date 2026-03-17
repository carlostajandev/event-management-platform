# order-service

Creates purchase orders from confirmed reservations. Owns `emp-orders`, `emp-outbox`, `emp-idempotency-keys`, and `emp-audit` DynamoDB tables.

**Port:** 8083

## Responsibilities

- Create a purchase order from a valid (ACTIVE) reservation
- Guarantee exactly-once order creation via idempotency key
- Publish order to SQS via transactional outbox (zero zombie orders)
- Record all state transitions in `emp-audit`

## Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/orders` | Create order (requires `X-Idempotency-Key` and `X-Correlation-Id` headers) |
| `GET` | `/api/v1/orders/{orderId}` | Get order by ID |
| `GET` | `/api/v1/orders/user/{userId}` | Get orders for a user |
| `GET` | `/actuator/health` | Health check |
| `GET` | `/actuator/prometheus` | Prometheus metrics |

### POST /api/v1/orders

**Headers:**
- `X-Idempotency-Key: <uuid>` — required; prevents duplicate charges
- `X-Correlation-Id: <uuid>` — propagated to all logs

**Request:**
```json
{
  "reservationId": "res_abc",
  "userId": "u_789"
}
```

**Response 201:**
```json
{
  "orderId": "ord_xyz",
  "reservationId": "res_abc",
  "userId": "u_789",
  "status": "PENDING",
  "totalAmount": 150000.00,
  "currency": "COP",
  "createdAt": "2026-03-16T10:00:00Z"
}
```

**Idempotency behavior:** If the same `X-Idempotency-Key` is submitted again within 24 hours, the original `OrderResponse` is returned (HTTP 200) with no side effects. The key lookup and order write happen in the same `TransactWriteItems` call — atomically.

**Error responses:**
- `409 Conflict` — reservation not ACTIVE or already used
- `400 Bad Request` — missing/invalid fields

## Key Design Decisions

- **Transactional outbox**: `TransactWriteItems([order, outboxMessage])` — the order and its SQS delivery intent are written atomically. If SQS fails later, `OutboxPoller` retries every 5 seconds. Zero zombie PENDING orders.
- **Idempotency**: `GetItem KEY#{idempotencyKey}` first. If found → return cached JSON. If not → `TransactWriteItems([order, outbox, idempotencyRecord])` with 24h TTL.
- **No direct SQS write**: The order-service never calls SQS. consumer-service owns SQS delivery via the outbox pattern.

## DynamoDB Tables

### emp-orders

| Key | Pattern |
|---|---|
| PK | `ORDER#{orderId}` |
| SK | `METADATA` |
| GSI1PK | `USER#{userId}` |
| GSI1SK | `DATE#{createdAt.iso}` |

### emp-outbox

| Key | Pattern |
|---|---|
| PK | `OUTBOX#{orderId}` |
| SK | `METADATA` |
| GSI1PK | `STATUS#{PENDING\|PUBLISHED}` |
| GSI1SK | `CREATED#{createdAt.iso}` |
| TTL | `ttl` (24 hours) |

### emp-idempotency-keys

| Key | Pattern |
|---|---|
| PK | `KEY#{idempotencyKey}` |
| SK | `METADATA` |
| TTL | `ttl` (24 hours) |

## Running Locally

```bash
docker compose -f infrastructure/docker-compose.yml up localstack -d
./mvnw spring-boot:run -pl services/order-service -Dspring-boot.run.profiles=local
```

## Running Tests

```bash
./mvnw test -pl shared/domain,shared/infrastructure,services/order-service
```
