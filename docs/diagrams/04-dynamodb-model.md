# DynamoDB Data Model & Access Patterns

## Tables Overview

```
emp-events          → event catalog
emp-tickets         → inventory per event (with GSI)
emp-orders          → purchase orders (with GSI)
emp-idempotency     → request deduplication (TTL)
emp-audit           → state change history
emp-shedlock        → distributed scheduler lock
```

---

## emp-events

```
PK: eventId (String)   e.g. "evt_a1b2c3d4-..."

Item shape:
{
  "eventId":       "evt_a1b2c3d4-...",   PK
  "name":          "Bad Bunny World Tour 2027",
  "description":   "...",
  "eventDate":     "2027-06-15T20:00:00Z",
  "venueName":     "Estadio El Campín",
  "venueCity":     "Bogotá",
  "venueCountry":  "Colombia",
  "totalCapacity": 50000,
  "ticketPrice":   350000,
  "currency":      "COP",
  "status":        "DRAFT",
  "version":       1,
  "createdAt":     "2027-01-01T00:00:00Z",
  "updatedAt":     "2027-01-01T00:00:00Z"
}

Access patterns:
  GET by eventId     → GetItem(PK)                          O(1)
  LIST all events    → Scan (with pagination skip/take)     O(n)
```

---

## emp-tickets

```
PK: ticketId (String)  e.g. "tkt_x1y2z3w4-..."

Item shape:
{
  "ticketId":    "tkt_x1y2z3w4-...",   PK
  "eventId":     "evt_a1b2c3d4-...",   GSI PK
  "status":      "AVAILABLE",          GSI SK
  "userId":      null,                 set on RESERVED
  "orderId":     null,                 set on RESERVED
  "price":       350000,
  "currency":    "COP",
  "reservedAt":  null,
  "expiresAt":   null,                 set on RESERVED (TTL candidate)
  "confirmedAt": null,                 set on SOLD
  "version":     1                     optimistic locking counter
}

GSI: eventId-status-index
  PK: eventId   SK: status
  Projection: ALL

Access patterns:
  GET by ticketId          → GetItem(PK)                   O(1)
  QUERY AVAILABLE tickets  → Query(GSI, eventId + status=AVAILABLE) O(k) where k=results
  UPDATE with version      → UpdateItem with ConditionExpression    O(1) + atomicity
```

---

## emp-orders

```
PK: orderId (String)   e.g. "ord_p1q2r3s4-..."

Item shape:
{
  "orderId":       "ord_p1q2r3s4-...",  PK
  "userId":        "usr_001",           GSI PK
  "eventId":       "evt_a1b2c3d4-...",
  "ticketIds":     ["tkt_x1y2z3w4"],    list of reserved ticket IDs
  "quantity":      2,
  "totalAmount":   700000,
  "currency":      "COP",
  "status":        "PENDING",
  "failureReason": null,
  "version":       1,
  "createdAt":     "2027-06-01T10:00:00Z",
  "updatedAt":     "2027-06-01T10:00:00Z"
}

GSI: userId-index
  PK: userId
  Projection: ALL

Access patterns:
  GET by orderId       → GetItem(PK)                O(1)
  LIST orders by user  → Query(GSI, userId)         O(k)
```

---

## emp-idempotency

```
PK: idempotencyKey (String)   UUID provided by client

Item shape:
{
  "idempotencyKey": "550e8400-e29b-41d4-a716-446655440000",  PK
  "responseJson":   "{\"orderId\":\"ord_...\",\"status\":\"RESERVED\",...}",
  "createdAt":      "2027-06-01T10:00:00Z",
  "expiresAt":      1748779200   ← Unix epoch (TTL attribute)
}

TTL: expiresAt
  DynamoDB deletes items automatically when expiresAt < now
  Default TTL: 24 hours (configurable via RESERVATION_TTL_MINUTES)
  No Lambda, no cron job, no cleanup needed

Access patterns:
  CHECK exists   → GetItem(PK)   O(1)  → if found: return responseJson
  SAVE response  → PutItem(PK)   O(1)  → cache the response
```

---

## emp-audit

```
PK: entityId (String)    e.g. "tkt_x1y2z3w4"
SK: timestamp (String)   ISO-8601 e.g. "2027-06-01T10:00:00.000Z"

Item shape:
{
  "entityId":       "tkt_x1y2z3w4-...",   PK
  "timestamp":      "2027-06-01T10:00:00.000Z",  SK
  "entityType":     "TICKET",
  "action":         "RESERVED",
  "userId":         "usr_001",
  "correlationId":  "550e8400-...",
  "previousStatus": "AVAILABLE",
  "newStatus":      "RESERVED",
  "metadata":       {"orderId": "ord_...", "expiresAt": "..."}
}

Access patterns:
  GET history for entity  → Query(PK=entityId)           O(k) chronological
  GET at specific time    → Query(PK=entityId, SK=timestamp) O(1)
```

---

## emp-shedlock

```
PK: _id (String)   lock name e.g. "release-expired-reservations"

Item shape:
{
  "_id":       "release-expired-reservations",  PK
  "lockUntil": "2027-06-01T10:00:55Z",  ← now + lockAtMostFor(55s)
  "lockedAt":  "2027-06-01T10:00:00Z",
  "lockedBy":  "ip-10-0-10-45"          ← ECS task private IP
}

ShedLock behavior:
  1. Task tries to acquire lock: PutItem(PK) WHERE lockUntil < now
  2. If success → runs scheduler job
  3. On completion → update lockUntil = now + lockAtLeastFor(30s)
  4. If instance crashes → lockAtMostFor(55s) guarantees eventual release
```
