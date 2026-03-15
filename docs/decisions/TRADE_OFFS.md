# Trade-offs, Limitations & Production Improvements

## Decisions I Would Change in Production

### 1. Expired Reservation Scanner — Full Table Scan

**Current situation:**
The scheduler queries DynamoDB for tickets with `status=RESERVED` and `expiresAt < now` using a `Scan` operation on the `emp-tickets` table.

**Problem at scale:**
With millions of tickets, a full table scan is expensive ($0.25 per million RCUs) and slow — scan time grows linearly with table size.

**Production solution:**
```
Option A — GSI on (status, expiresAt):
  Query index WHERE status=RESERVED AND expiresAt < :now
  Cost: additional GSI write per ticket update, but queries are fast and cheap.

Option B — DynamoDB TTL + Streams:
  Set TTL on ticket.expiresAt
  DynamoDB TTL fires → Stream event → Lambda releases ticket
  Fully serverless, zero scheduler overhead, scales infinitely.
  Trade-off: TTL deletion is not instantaneous (can take up to 48h in worst case).

Option C — EventBridge Scheduler:
  Per-reservation scheduled event at exact expiresAt time
  Exact precision, no polling, no scan
  Higher cost per reservation ($0.000001 per invocation)
```

**Why not done here:**
For a technical assessment with controlled data volume, the scan approach demonstrates the concept correctly. GSI option would be the first production improvement.

---

### 2. SQS Standard — Not FIFO

**Current situation:**
SQS Standard queue — at-least-once delivery, no ordering guarantee.

**Problem:**
If a message is retried quickly (network blip), the same order could be processed twice concurrently by two different consumer instances.

**Mitigation in place:**
`ProcessOrderService` checks for final state before processing. If order is already `CONFIRMED` or `CANCELLED`, the message is acknowledged and discarded silently.

**Production improvement:**
```
SQS FIFO with MessageGroupId = orderId
  → at-most-once processing per order group
  → messages for the same order processed in sequence
  → higher cost ($0.50/million vs $0.40/million for Standard)
  → 300 messages/sec per message group limit (acceptable for ticketing)
```

---

### 3. No Integration Tests with Real Infrastructure

**Current situation:**
65 unit tests with Mockito mocks for repositories and message publishers. No tests spin up real DynamoDB or SQS.

**Problem:**
- DynamoDB conditional write behavior not verified end-to-end
- SQS at-least-once delivery not tested
- Real pagination behavior with DynamoDB not tested
- JaCoCo branch coverage artificially low (45%) because infrastructure classes are excluded

**Production improvement:**
```java
@SpringBootTest
@Testcontainers
class ReservationIntegrationTest {

    @Container
    static LocalStackContainer localStack =
        new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.6"))
            .withServices(Service.DYNAMODB, Service.SQS);

    // Tests with REAL DynamoDB + SQS behavior
    // - Conditional write conflicts
    // - SQS retry and DLQ behavior
    // - TTL verification
    // - GSI queries
}
```

This would bring branch coverage back to 70%+ and validate actual DynamoDB behavior.

---

### 4. No API Versioning Strategy

**Current situation:**
All endpoints use `/api/v1/` prefix. No formal versioning strategy beyond the URL prefix.

**Problem:**
Breaking changes to the API (removing fields, changing types) would break existing clients with no migration path.

**Production strategy:**
```
Option A — URL versioning (current): /api/v1/, /api/v2/
  Pro: Simple, cache-friendly
  Con: URL proliferation, hard to deprecate

Option B — Header versioning: Accept: application/vnd.nequi.v2+json
  Pro: Clean URLs, clients opt-in
  Con: Harder to test in browser, less visible

Option C — Consumer-driven contracts (Pact):
  Each consumer defines what fields it needs
  Provider verifies all contracts on every build
  Breaking changes detected before deployment
```

---

### 5. No Circuit Breaker on Web Layer

**Current situation:**
`@CircuitBreaker` is applied on DynamoDB repositories and SQS publisher. If DynamoDB is down, the circuit opens and requests fail fast — but the failure propagates to the client as a 500.

**Production improvement:**
Implement fallback responses at the handler level:

```java
// EventHandler.java — with fallback
return getEventUseCase.findById(eventId)
    .onErrorResume(CallNotPermittedException.class, ex ->
        // Circuit is open — return cached/stale data or 503
        Mono.error(new ServiceUnavailableException("Event service temporarily unavailable")));
```

With Redis as a read-through cache, frequently accessed events could be served from cache even when DynamoDB is degraded.

---

### 6. Single NAT Gateway

**Current situation:**
One NAT Gateway in AZ-1a. All three AZs share it for outbound internet traffic.

**Problem:**
If AZ-1a goes down, NAT Gateway goes with it. ECS tasks in AZ-1b and AZ-1c lose outbound internet access (ECR pull, external API calls).

**Production fix:**
One NAT Gateway per AZ — ECS tasks in each AZ use their local NAT Gateway:
```hcl
resource "aws_nat_gateway" "main" {
  count         = length(var.availability_zones)
  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id
}
```
Cost: ~$45/month per extra NAT Gateway — justified in production, not for this assessment.

---

### 7. No Request Tracing End-to-End

**Current situation:**
`X-Correlation-Id` is generated/propagated in MDC for log correlation. X-Ray SDK is in the IAM policy but not fully instrumented in code.

**Production improvement:**
Full distributed tracing with AWS X-Ray:
```java
// Add to WebFlux filter chain
@Bean
public WebFilter tracingFilter() {
    return (exchange, chain) -> {
        String traceId = exchange.getRequest()
            .getHeaders().getFirst("X-Amzn-Trace-Id");
        // Propagate through all reactive chains
        return chain.filter(exchange)
            .contextWrite(ReactorAWSXRay.storeSegment(segment));
    };
}
```

With full X-Ray instrumentation: end-to-end latency visible from ALB → ECS → DynamoDB → SQS per request.

---

## What I Would Do With More Time

| Priority | Improvement | Impact |
|---|---|---|
| P0 | Testcontainers + LocalStack integration tests | Correctness confidence, coverage to 80%+ |
| P0 | GSI on (status, expiresAt) for expiry scanner | Eliminates full table scan at scale |
| P1 | SQS FIFO for order processing | Eliminates edge case of parallel processing |
| P1 | Redis read-through cache for events | Sub-millisecond reads, DynamoDB fallback |
| P1 | Full X-Ray distributed tracing | End-to-end request visibility |
| P2 | NAT Gateway per AZ | True multi-AZ resilience |
| P2 | Consumer-driven contracts (Pact) | API compatibility guaranteed |
| P2 | OpenAPI spec generation | Auto-generated documentation, client SDKs |
| P3 | Event sourcing for ticket state | Full audit trail from state changes |
| P3 | CQRS read model | Separate optimized read path for availability queries |