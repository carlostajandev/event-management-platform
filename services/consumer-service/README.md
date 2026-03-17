# consumer-service

Processes purchase orders asynchronously from SQS. No ALB — internal worker only.

**Port:** 8084 (management/health only, no public endpoints)

## Responsibilities

- Poll `emp-purchase-orders` SQS queue via `@SqsListener` (Spring Cloud AWS 3.x)
- Process orders: validate reservation ACTIVE → mark reservation CONFIRMED → mark order CONFIRMED
- Deliver outbox messages to SQS via `OutboxPoller` every 5 seconds
- Send failed messages to DLQ after 3 failures
- Record all state transitions in `emp-audit`

## No Public API

consumer-service has no REST endpoints exposed via ALB. All processing is event-driven via SQS.

| Path | Description |
|---|---|
| `GET /actuator/health` | Health check (ECS only) |
| `GET /actuator/prometheus` | Prometheus metrics |

## Key Design Decisions

- **`@SqsListener(acknowledgementMode = ON_SUCCESS)`**: Message is deleted from SQS only when `CompletableFuture<Void>` completes successfully. If processing fails, SQS re-delivers (up to 3 times → DLQ). Replaces the deprecated `SqsMessageDeletionPolicy.ON_SUCCESS` removed in Spring Cloud AWS 3.x.
- **Idempotency**: `ProcessOrderService` checks `order.status() == CONFIRMED` first → `Mono.empty()` if already processed. SQS at-least-once delivery is safe.
- **Outbox polling**: `OutboxPoller` queries `GSI1: STATUS#PENDING AND CREATED#{date}` every 5 seconds, publishes to SQS, then marks the outbox item `PUBLISHED`. Latency: up to 5 seconds. Production upgrade: DynamoDB Streams + Lambda → <500ms.
- **DLQ monitoring**: CloudWatch alarm triggers PagerDuty on any DLQ message (`> 0` threshold). `emp-purchase-orders-dlq` retains failed messages for manual inspection.

## Message Format (SQS)

```json
{
  "orderId": "ord_xyz",
  "reservationId": "res_abc",
  "userId": "u_789",
  "seatsCount": 2,
  "eventId": "evt_123",
  "totalAmount": 150000.00,
  "currency": "COP"
}
```

## DynamoDB Tables (read/write)

| Table | Access |
|---|---|
| `emp-orders` | Read order status; update to CONFIRMED/FAILED |
| `emp-reservations` | Update status to CONFIRMED |
| `emp-outbox` | Read PENDING outbox items; mark PUBLISHED |
| `emp-audit` | Write state transitions |

## SQS Configuration

| Setting | Value | Reason |
|---|---|---|
| Visibility timeout | 30s | Longer than max processing time — prevents parallel processing |
| Long polling | 20s | ~95% reduction in empty receive calls |
| Max receive count | 3 | After 3 failures → DLQ |
| Encryption | KMS (`alias/aws/sqs`) | At-rest encryption |

## Running Locally

```bash
docker compose -f infrastructure/docker-compose.yml up localstack -d
./mvnw spring-boot:run -pl services/consumer-service -Dspring-boot.run.profiles=local
```

## Running Tests

```bash
./mvnw test -pl shared/domain,shared/infrastructure,services/consumer-service
```
