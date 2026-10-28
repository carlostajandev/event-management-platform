# ADR 0002 — Purchase flow: hold on request, validate in the consumer

- **Status:** Accepted
- **Date:** 2026-10-28

## Context

Two requirements shape the flow:

- **RF2:** when a user starts a purchase, the tickets must be held for at most 10 minutes; if the
  purchase is not confirmed in time, they return to the available inventory.
- **RF3:** purchase requests must be *enqueued immediately and return an order id*; a consumer
  processes them asynchronously, *validating real availability, updating inventory and changing the
  order status*.

Read literally, RF3 alone suggests "enqueue first, reserve in the consumer". That leaves the user without
a hold until the consumer runs, which contradicts the intent of RF2 under a flash-sale load.

## Decision

Hold the tickets and enqueue in the **same transaction**, then let the consumer validate the hold.

```
POST /api/v1/orders           (Idempotency-Key required)
  TransactWriteItems:
    Update inventory   available -= n, reserved += n          IF available >= :n
    Put    order       status = RESERVED, expiresAt = now+10m  IF attribute_not_exists(PK)
    Put    idempotency key + request hash                      IF attribute_not_exists(PK)
    Put    audit       AVAILABLE -> RESERVED
    Put    outbox      OrderPlaced
  -> 202 Accepted { orderId, status: RESERVED, expiresAt }

worker: outbox relay -> SQS

worker: SQS listener (at-least-once, idempotent)
  validate: order is RESERVED and expiresAt > now
  TransactWriteItems:
    Update order       RESERVED -> PENDING_CONFIRMATION        IF status = RESERVED AND expiresAt > :now
    Update inventory   reserved -= n, pendingConfirmation += n
    Put    audit
  if the condition fails (expired, released, already processed) -> ack, no state change

POST /api/v1/orders/{id}/confirm   (simulated payment)
  TransactWriteItems:
    Update order       PENDING_CONFIRMATION -> SOLD            IF status = PENDING_CONFIRMATION AND expiresAt > :now
    Update inventory   pendingConfirmation -= n, sold += n
    Put    audit
  order still RESERVED -> 409 "order is being processed" (client polls GET /orders/{id})

worker: expiry job (@Scheduled, GSI status#expiresAt)
  for RESERVED | PENDING_CONFIRMATION with expiresAt <= now:
  TransactWriteItems:
    Update order       -> AVAILABLE                            IF status = :from
    Update inventory   from-counter -= n, available += n
    Put    audit
```

Complimentary tickets: `POST /api/v1/events/{id}/complimentary` creates an order directly in
`COMPLIMENTARY` (`available -= n, complimentary += n`, conditional on `available >= :n`). Terminal and
not counted as revenue.

### Rules

- **One `expiresAt` for the whole hold.** It is set once at creation and applies to both `RESERVED` and
  `PENDING_CONFIRMATION`, so a hold never exceeds the 10 minutes of RF2. (The kickoff proposed a longer
  timeout for `PENDING_CONFIRMATION`; a single deadline is simpler and matches the spec.)
- **Released orders show `AVAILABLE`.** RF4 lists `AVAILABLE` among order states; an order whose hold
  expired reports `AVAILABLE` (its tickets went back to the pool), with the reason in the audit trail.
- **Races are settled by conditions, not by locks.** The consumer, `/confirm` and the expiry job all
  condition on the expected `from` state (and on `expiresAt` where relevant), so exactly one wins.
- **Retries:** only on `TransactionConflict` cancellation reasons, inside `Mono.defer`, with jittered
  backoff (3 attempts). `ConditionalCheckFailed` on the inventory → `409 SOLD_OUT`; on the idempotency
  item → replay the stored response, or `422` if the request hash differs.
- **Invariant:** `available + reserved + pendingConfirmation + sold + complimentary = total`.

## Alternatives considered

1. **Enqueue only; reserve in the consumer.** Most literal reading of RF3. Rejected: the user does not
   know whether they got tickets until the consumer runs, and under a spike the queue lag becomes the
   user's wait. It also moves the oversell decision to a place where the client cannot be told
   synchronously.
2. **Reserve and confirm synchronously, no queue.** Rejected: violates RF3.
3. **Publish to SQS directly after the write.** Rejected: dual write; if SQS fails after DynamoDB
   commits, the order never gets processed. The outbox makes the enqueue part of the transaction.

## Consequences

- The client receives a real hold immediately (`202` + `orderId` + `expiresAt`), and the queue still
  carries the asynchronous processing RF3 asks for.
- The consumer is idempotent by construction: a duplicate or late message fails the condition and is
  acknowledged without side effects.
- Each transaction touches the event's inventory item, which is the hot item under load (see ADR 0001).
- `TransactWriteItems` costs two write units per item; the practical ceiling per event is roughly half
  of a plain conditional update. Measured and documented in the load test.
