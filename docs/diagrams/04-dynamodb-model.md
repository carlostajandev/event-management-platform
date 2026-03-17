# DynamoDB Data Model — Access Pattern Driven Design

## Why NoSQL, Not SQL

**Anti-pattern (v1):** One DynamoDB item per ticket → 50,000 PutItem calls for a single concert.
**v2 approach:** Inventory as atomic counter → 1 UpdateItem regardless of ticket count.

The model is designed around access patterns, not entities. No normalization.
Every table has PAY_PER_REQUEST billing, PITR enabled, encryption at rest.

---

## emp-events

**Access patterns:**
- Get event by ID: `GetItem PK=EVENT#id SK=METADATA` — O(1)
- List by status: `Query GSI1 GSI1PK=STATUS#ACTIVE` — O(results)
- Reserve tickets: `UpdateItem conditional` — O(1), atomic, no lock

```
PK            | SK       | name       | totalCapacity | availableCount | version | status | GSI1PK
EVENT#evt_123 | METADATA | Rock Fest  | 10000         | 9998           | 47      | ACTIVE | STATUS#ACTIVE
```

**Atomic counter decrement (conditional write):**
```
UpdateExpression:  SET availableCount = availableCount - :n, version = version + 1
ConditionExpression: availableCount >= :n AND version = :expected
```
`ConditionalCheckFailedException` → `retryWhen(backoff 100ms, max 3)` → 409 Conflict

**Write sharding for events >10K tickets** (hot partition prevention):
```
PK=EVENT#evt_123#SHARD_0  availableCount=1250
PK=EVENT#evt_123#SHARD_1  availableCount=1250
...
PK=EVENT#evt_123#SHARD_7  availableCount=1250
```
Reservation: `ThreadLocalRandom.current().nextInt(8)` picks shard.
Availability: aggregate all 8 shards in parallel `Flux` then sum.

---

## emp-reservations

**Access patterns:**
- Get by ID: `GetItem PK=RESERVATION#id SK=USER#userId` — O(1)
- Find expired: `Query GSI1 GSI1PK=STATUS#ACTIVE AND GSI1SK <= now` — O(results), NOT O(table)
- Update status: `UpdateItem SET #s = :newStatus`

```
PK               | SK           | eventId  | seatsCount | status | expiresAt        | ttl        | GSI1PK        | GSI1SK
RESERVATION#r_01 | USER#u_789   | evt_123  | 2          | ACTIVE | 2026-03-16T10:10Z| 1710000600 | STATUS#ACTIVE | 2026-03-16T10:10Z
```

**TTL expiry:** `ttl = now + 600 seconds` (epoch). DynamoDB auto-deletes at zero compute cost.
**Reconciliation:** GSI1 query with `GSI1SK <= Instant.now().toString()` — O(results), NOT a scan.

---

## emp-orders

**Access patterns:**
- Get by ID: `GetItem PK=ORDER#id` — O(1)
- Get user orders: `Query GSI1 GSI1PK=USER#userId` — O(results)
- Update status: `UpdateItem SET #status = :confirmed`

```
PK            | SK               | status               | idempotencyKey | totalAmount | GSI1PK
ORDER#ord_789 | RESERVATION#r_01 | PENDING_CONFIRMATION | uuid-001       | 300000.00   | USER#u_789
```

---

## emp-outbox

**Access patterns:**
- Find unpublished: `Query GSI1 GSI1PK=PUBLISHED#false` — OutboxPoller every 5s
- Mark published: `UpdateItem SET published=true` (removes from GSI1)
- Auto-cleanup: TTL 24h

```
PK            | SK                    | aggregateId | eventType    | payload (JSON)      | published | GSI1PK          | ttl
OUTBOX#msg_01 | CREATED_AT#2026-03-16 | ord_789     | ORDER_PLACED | {orderId:ord_789...}| false     | PUBLISHED#false | 1710087600
```

**Atomicity guarantee (v2 fix — was dual-write in v1):**
```java
transactWriteItems([
    Put { table: "emp-orders",  item: order },       // always together
    Put { table: "emp-outbox",  item: outboxMsg }     // or not at all
])
// If SQS is down: OutboxPoller retries every 5s until SQS recovers
// Zero PENDING zombie orders
```

---

## emp-idempotency-keys

**Access patterns:**
- Check before processing: `GetItem PK=KEY#uuid SK=IDEMPOTENCY` — O(1)
- Cache response: included in `TransactWriteItems` with order creation
- Auto-expire: TTL 24h

```
PK          | SK           | orderId   | cachedResponseJson         | ttl
KEY#uuid-01 | IDEMPOTENCY  | ord_789   | {"id":"ord_789","status":…} | 1710087600
```

**Idempotency flow:**
1. `GetItem KEY#uuid` → if found → return `cachedResponseJson` deserialized (no business logic)
2. If not found → process → `TransactWriteItems([order, outbox, idempotencyRecord])`

---

## emp-audit

**Access patterns:**
- Entity history: `Query PK=AUDIT#entityId ORDER BY SK ASC` — O(transitions)
- Auto-cleanup: TTL 90 days (compliance retention)

```
PK            | SK                            | fromStatus | toStatus  | userId  | entityType   | ttl
AUDIT#r_01    | TIMESTAMP#2026-03-16T10:00:00Z | NONE       | ACTIVE    | u_789   | RESERVATION  | 1717862400
AUDIT#r_01    | TIMESTAMP#2026-03-16T10:15:00Z | ACTIVE     | CONFIRMED | u_789   | RESERVATION  | 1717862400
AUDIT#ord_789 | TIMESTAMP#2026-03-16T10:15:00Z | PENDING    | CONFIRMED | u_789   | ORDER        | 1717862400
```

---

## Table Summary

| Table | Owner Service | TTL | GSI | Purpose |
|---|---|---|---|---|
| emp-events | event-service | — | STATUS index | Events + atomic counter |
| emp-reservations | reservation-service | 10 min | STATUS+expiresAt | TTL auto-expiry |
| emp-orders | order-service | — | USER index | Order state |
| emp-outbox | order-service (write) / consumer-service (read) | 24h | PUBLISHED index | Guaranteed delivery |
| emp-idempotency-keys | order-service | 24h | — | Dedup cache |
| emp-audit | reservation-service + consumer-service | 90 days | — | Compliance trail |
