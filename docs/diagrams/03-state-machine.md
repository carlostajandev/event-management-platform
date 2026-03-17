# State Machines — Microservices v2

## Reservation State Machine

```mermaid
stateDiagram-v2
    [*] --> ACTIVE : POST /reservations\nconditional write\navailableCount -= n\nversion check\nAudit: NONE → ACTIVE

    ACTIVE --> EXPIRED : DynamoDB TTL\nauto-delete (10 min)\ncompensation: availableCount += n\nAudit: ACTIVE → EXPIRED

    ACTIVE --> CONFIRMED : consumer-service\nprocessOrder() success\nAudit: ACTIVE → CONFIRMED

    ACTIVE --> CANCELLED : DELETE /reservations/{id}\nuser explicit cancel\ncompensation: availableCount += n\nAudit: ACTIVE → CANCELLED

    CONFIRMED --> [*] : terminal state
    EXPIRED --> [*] : terminal state
    CANCELLED --> [*] : terminal state
```

**Key rule:** Every transition is atomic (TransactWriteItems or UpdateItem + conditional write) and recorded in emp-audit with 90-day TTL.

## Order State Machine

```mermaid
stateDiagram-v2
    [*] --> PENDING_CONFIRMATION : POST /orders\nTransactWriteItems atomic:\n  order + outbox message\nidempotency key cached 24h\nAudit: NONE → PENDING_CONFIRMATION

    PENDING_CONFIRMATION --> CONFIRMED : consumer-service\n@SqsListener ON_SUCCESS\nAudit: PENDING → CONFIRMED

    PENDING_CONFIRMATION --> FAILED : SQS consumer fails\n3 retries → DLQ\nmanual intervention needed\nAudit: PENDING → FAILED

    CONFIRMED --> COMPLETED : payment captured\ntickets issued
    CONFIRMED --> [*] : terminal

    FAILED --> [*] : terminal
    COMPLETED --> [*] : terminal
```

## Outbox Message State Machine

```mermaid
stateDiagram-v2
    [*] --> UNPUBLISHED : TransactWriteItems\nwith order creation\npublished=false\nGSI1PK=PUBLISHED#false

    UNPUBLISHED --> PUBLISHED : OutboxPoller\nevery 5s reads GSI1\npublishes to SQS\nmarks published=true

    PUBLISHED --> [*] : TTL 24h\nDynamoDB auto-deletes
    UNPUBLISHED --> [*] : TTL 24h (safety net)
```

## Java 25 Pattern Matching — Exception → HTTP Status

```java
// GlobalErrorHandler.java — sealed switch, no default needed for known types
HttpStatus status = switch (ex) {
    case EventNotFoundException ignored          -> HttpStatus.NOT_FOUND;           // 404
    case ReservationNotFoundException ignored    -> HttpStatus.NOT_FOUND;           // 404
    case OrderNotFoundException ignored          -> HttpStatus.NOT_FOUND;           // 404
    case TicketNotAvailableException ignored     -> HttpStatus.CONFLICT;            // 409
    case ConcurrentModificationException ignored -> HttpStatus.CONFLICT;            // 409
    case IdempotencyConflictException ignored    -> HttpStatus.UNPROCESSABLE_ENTITY;// 422
    case IllegalArgumentException ignored        -> HttpStatus.BAD_REQUEST;         // 400
    default -> {
        log.error("Unhandled exception", ex);
        yield HttpStatus.INTERNAL_SERVER_ERROR;                                     // 500
    }
};
```

## AuditService Integration (v2 Fix)

In v1 the `AuditService` was implemented but **never called**. In v2 every state transition calls `auditRepository.save()`:

| Service | Transition | Trigger |
|---|---|---|
| reservation-service | NONE → ACTIVE | `ReserveTicketsService.execute()` |
| reservation-service | ACTIVE → CANCELLED | `CancelReservationService.execute()` |
| reservation-service | ACTIVE → EXPIRED | `ReleaseExpiredReservationsService.execute()` |
| consumer-service | NONE → PENDING_CONFIRMATION | order-service via `CreateOrderService` |
| consumer-service | PENDING → CONFIRMED | `ProcessOrderService.process()` |
| consumer-service | ACTIVE → CONFIRMED | `ProcessOrderService.process()` (reservation) |
