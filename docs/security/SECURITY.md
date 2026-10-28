# Security

How the system handles the risks the spec names: secrets and credentials, malicious retries, idempotency and
resource abuse. Each control points to the code or test that implements it. Controls marked **(Phase 7)** belong
to the AWS deployment and are described in the Terraform code.

## Secrets and credentials

| Control | Where |
|---|---|
| No secrets in the repository. The only credentials are dummy values for the local emulators, which do not validate them. | `docker-compose.yml`, `src/test/.../support/LocalAws.java` |
| The application never reads access keys. The AWS SDK uses the default credentials chain: locally the dummy env vars, on AWS the ECS task role. | `AwsClientsConfig` |
| Secret scanning of the full Git history on every push (gitleaks). | `.github/workflows/ci-cd.yml`, job `secrets` |
| Weekly dependency, base image and CI action updates. | `.github/dependabot.yml` |
| **(Phase 7)** One IAM role per role (`api`, `worker`), least privilege: the api cannot read the queue and the worker cannot create events. No long-lived keys anywhere; CI deploys through GitHub OIDC. | `infrastructure/terraform` |
| **(Phase 7)** DynamoDB and SQS encrypted at rest with KMS; traffic to them stays inside the VPC through VPC endpoints. | `infrastructure/terraform` |

## Idempotency and malicious retries

| Attack | Control | Where |
|---|---|---|
| Double click or client retry creates a second order | `Idempotency-Key` header is mandatory on every write. The key is stored **in the same transaction** as the order, with `attribute_not_exists`, so two concurrent requests with one key create one order; the loser gets the original. | `IdempotentReplay`, `DynamoDbOrderRepository`, `OrderUseCasesTest` |
| Reusing a key with a different body to get a different result | The key is stored with a SHA-256 of the fields that define the operation. Same key with a different body → `422 IDEMPOTENCY_KEY_REUSED`, nothing written. | `RequestFingerprint`, `IdempotencyRecord` |
| Guessing another user's key to read their order | Keys are scoped by caller: the stored key is `userId + key`, so the same key from two users means two operations. | `IdempotencyKey` |
| Retrying after a timeout to buy twice | A timeout answers `503 TIMEOUT`: "retry with the same key". The retry returns the stored order if the first write committed. | `ApiExceptionHandler`, `load-test/RESULTS.md` |
| Replaying a queue message to move an order twice | Every transition is conditional on the expected state, so a duplicate or late message fails the condition and is acknowledged without changes. Poison messages go to a DLQ after 3 receives. | `ProcessPlacedOrderUseCase`, `PurchaseFlowIntegrationTest`, `elasticmq.conf` |
| Confirming an expired hold | Confirmation is conditional on `expiresAt > now` inside the transaction, not checked before it. | `ConfirmOrderUseCase`, `DynamoDbOrderRepositoryIntegrationTest` |
| Overselling through concurrency | `available >= :n` is the condition of the inventory update; no read-then-write. 200 concurrent buyers for 50 tickets reserve exactly 50. | `DynamoDbOrderRepositoryIntegrationTest` |

Idempotency records expire with DynamoDB TTL after 24 hours, so the table does not grow without bound.

## Resource abuse

| Abuse | Control | Where |
|---|---|---|
| Flooding the API from one user | Token bucket per `X-User-Id`: 5 requests/s, burst 10 → `429 RATE_LIMITED` with `Retry-After`. | `RequestProtectionFilter`, `RequestProtectionFilterTest` |
| Flooding from one address with many user ids | Token bucket per client address: 100 requests/s, burst 200. **(Phase 7)** Behind the load balancer the peer address is the balancer, so this job moves to AWS WAF rate-based rules. | `RequestProtectionFilter` |
| Exhausting memory with new keys | Rate-limit buckets live in a bounded cache (100,000 keys, idle keys forgotten after a minute). | `RateLimiter` |
| Overload that makes DynamoDB slow for everyone | Cap on requests in flight per instance → `503 OVERLOADED` before any write, so the outcome is certain and retrying is safe. | `RequestProtectionFilter`, `load-test/RESULTS.md` |
| Hoarding tickets | Max 6 tickets per order; holds expire after 10 minutes and the tickets return to the pool. | `Order`, `ReleaseExpiredHoldsUseCase` |
| Large or malformed payloads | Request bodies over 16 KB rejected; every field validated (lengths, ranges, currency format, future date). Header values bounded (user id 64, idempotency key 128). | `application.yml`, request DTOs, `UserId`, `IdempotencyKey` |
| Health checks used to amplify load on DynamoDB | Readiness is shallow and never calls DynamoDB; health endpoints are not rate limited but do no remote work. | `application.yml`, `DynamoDbHealthIndicator` |

## Information exposure

- Errors are RFC 9457 problem documents with a stable `code`; stack traces and internal exception messages
  are never returned. Unexpected errors answer a generic `500`.
- Reading or confirming another user's order answers `404`, not `403`, so order ids cannot be probed.
- Only `health` and `info` actuator endpoints are exposed; `info` shows the role and nothing else.
- The container runs as a non-root user on a JRE-only Alpine image.

## Declared limitation: authentication

Authentication is out of scope (ADR 0003). The caller identity arrives in `X-User-Id` and admin operations
require `X-User-Role: admin`, so locally any client can claim any identity. The header is read in one class
(`HeaderCurrentCaller`); in production it is replaced by a JWT resource server (Amazon Cognito) without
changing domain or application code. Ownership checks and per-user limits are already enforced on whatever
identity that class returns.
