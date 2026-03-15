# Ticket State Machine

## States and Transitions

```
┌─────────────────────────────────────────────────────────────────────┐
│                        STATE: AVAILABLE                              │
│                     (initial state on creation)                      │
└──────────┬──────────────────────────────────────────────────────────┘
           │
           ├──► reserve(userId, orderId, expiresAt)
           │         Guard: ticket is AVAILABLE
           │         Action: set status=RESERVED, version+1
           │         ConditionExpression enforces atomicity
           │
           ├──► complimentary()
           │         Guard: ticket is AVAILABLE
           │         Action: set status=COMPLIMENTARY (FINAL)
           │
           ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        STATE: RESERVED                               │
│                  expiresAt: now + TTL (default 10 min)               │
└──────────┬────────────────────────────────────────────────────────-─┘
           │
           ├──► expiresAt < now (detected by scheduler every 60s)
           │         Action: release() → AVAILABLE
           │         Note: uses ShedLock — only ONE instance per cycle
           │
           ├──► confirmPending()
           │         Guard: ticket is RESERVED
           │         Action: set status=PENDING_CONFIRMATION, version+1
           │
           ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   STATE: PENDING_CONFIRMATION                         │
│                   (payment processing in progress)                    │
└──────────┬────────────────────────────────────────────────────────-─┘
           │
           ├──► sell()
           │         Guard: ticket is PENDING_CONFIRMATION
           │         Action: set status=SOLD, version+1 (FINAL)
           │
           └──► release() [payment failed]
                     Guard: ticket is PENDING_CONFIRMATION
                     Action: set status=AVAILABLE, version+1

┌─────────────────────────────────────────────────────────────────────┐
│                        STATE: SOLD (FINAL)                           │
│                     No transitions allowed from SOLD                  │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                    STATE: COMPLIMENTARY (FINAL)                      │
│                     No transitions allowed from COMPLIMENTARY         │
└─────────────────────────────────────────────────────────────────────┘
```

## Invalid Transitions → 409 Conflict

```
Any transition not listed above throws InvalidTicketStateException
which is mapped by GlobalErrorHandler to HTTP 409 Conflict.

Examples of INVALID transitions:
  SOLD        → RESERVED         (cannot un-sell a ticket)
  SOLD        → AVAILABLE        (cannot release a sold ticket)
  AVAILABLE   → PENDING_CONFIRM  (must go through RESERVED first)
  COMPLIMENT. → any state        (FINAL state, immutable)
```

## Implementation

```java
// TicketStateMachine.java
public Ticket reserve(Ticket ticket, String userId, String orderId, Instant expiresAt) {
    if (ticket.status() != TicketStatus.AVAILABLE) {
        throw new InvalidTicketStateException(
            ticket.ticketId(), ticket.status(), TicketStatus.RESERVED);
    }
    return ticket.toBuilder()
        .status(TicketStatus.RESERVED)
        .userId(userId)
        .orderId(orderId)
        .expiresAt(expiresAt)
        .reservedAt(Instant.now())
        .version(ticket.version() + 1)
        .build();
}
```

## Test Coverage — 17 Tests (TicketStateMachineTest)

```
✓ AVAILABLE → RESERVED        (valid)
✓ AVAILABLE → COMPLIMENTARY   (valid)
✓ RESERVED  → AVAILABLE       (expiry/release)
✓ RESERVED  → PENDING_CONF    (confirm pending)
✓ PENDING   → SOLD            (successful payment)
✓ PENDING   → AVAILABLE       (failed payment)
✓ SOLD      → RESERVED        (invalid → 409)
✓ SOLD      → AVAILABLE       (invalid → 409)
✓ COMPLT.   → RESERVED        (invalid → 409)
... and 8 more edge cases
```
