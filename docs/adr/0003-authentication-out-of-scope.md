# ADR 0003 — Authentication is out of scope (declared limitation)

- **Status:** Accepted
- **Date:** 2026-10-28

## Context

The spec evaluates security through secrets handling, idempotency, malicious retries and resource
abuse. It does not require user authentication. Implementing a full identity flow (token issuance,
key rotation, tests) would take time away from the parts the spec does evaluate.

## Decision

- No authentication in this version. The caller identity arrives in the `X-User-Id` header.
- The header is read in **one place only**, behind a port:

  ```java
  public interface CurrentUserProvider {
      Mono<UserId> currentUser(ServerWebExchange exchange);
  }
  ```

  Use cases receive a `UserId`; they never read HTTP headers. Replacing the adapter with a JWT-based one
  changes no domain or application code.
- **Ownership is still enforced:** `GET /orders/{id}` and `POST /orders/{id}/confirm` reject an order
  that belongs to a different `UserId` (`404`, to avoid leaking order existence).
- Admin operations (create event, complimentary tickets) are guarded by a static role header in local
  mode and documented as such.
- Controls that do not depend on identity are implemented: mandatory `Idempotency-Key`, max tickets per
  order, request size limits, validation, rate limiting per user header and per IP.

## Consequences

- Any client can claim any `X-User-Id` locally. This is stated in the README "Limitations" section.
- Rate limiting per user is only as strong as the header; the per-IP limit is the real guard locally.

## What would change in production

- Spring Security OAuth2 resource server validating JWTs from Amazon Cognito; `UserId` taken from the
  `sub` claim, admin from a Cognito group.
- AWS WAF rate-based rules in front of the ALB and API Gateway usage plans if exposed to partners.
- The `CurrentUserProvider` adapter is the only class that changes.
