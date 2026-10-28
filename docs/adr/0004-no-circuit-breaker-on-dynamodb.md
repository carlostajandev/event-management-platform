# ADR 0004 — No circuit breaker around DynamoDB

- **Status:** Accepted
- **Date:** 2026-10-28
- **Supersedes:** the Resilience4j `@CircuitBreaker` usage in v2

## Context

v2 wraps DynamoDB calls in Resilience4j circuit breakers. A circuit breaker earns its place when a
dependency can fail slowly and there is a useful fallback, or when failing fast protects other work.
For this system's single datastore, neither applies.

## Decision

Remove the circuit breaker around DynamoDB. Resilience is handled by:

- **AWS SDK v2 retry strategy** (standard/adaptive) with backoff for throttling and transient errors.
- **Explicit SDK timeouts** (`apiCallAttemptTimeout`, `apiCallTimeout`) so a slow call fails within a
  known bound instead of holding a connection.
- **Application-level retry** only for `TransactionConflict` (ADR 0002).
- **Backpressure at the edge:** rate limiting and a bounded connection pool, so overload is rejected
  with `429`/`503` before it reaches DynamoDB.
- **Alarms** on throttled requests, p99 latency and 5xx rate.

## Rationale

- There is **no fallback**: without DynamoDB the system cannot read inventory or take orders. An open
  breaker returns an error, which the timeouts already do.
- DynamoDB is a regional, multi-AZ managed service; its failure mode is throttling, which the SDK
  already retries with backoff.
- A breaker that counts business outcomes as failures can open under a flash sale: a burst of
  `ConditionalCheckFailed` (sold out) is normal behaviour, not an outage. Getting that classification
  wrong rejects valid purchases.
- One fewer component to configure, test and justify.

## When to revisit

- When an external, non-AWS dependency appears (for example a payment provider): a circuit breaker
  belongs there, with a fallback such as keeping the order in `PENDING_CONFIRMATION` and retrying later.
