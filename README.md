# Ticketing Platform v3

Reactive backend for high-demand ticket releases. Thousands of buyers can hit one event at once; the system
answers in milliseconds, never sells more tickets than exist, never sells the same ticket twice, and stays
correct when clients retry.

**Java 25 · Spring Boot 4 · WebFlux · DynamoDB · SQS · Docker · Terraform (AWS ECS Fargate)**

One deployable runs as two independently scalable roles:

| Role | Does | Scales on |
|---|---|---|
| `api` | Reactive REST API, idempotency, rate limits and load shedding | Requests |
| `worker` | Outbox relay to SQS, order consumer, release of expired holds | Queue depth |

The previous microservices version (v2) is preserved under the tag [`v2-final`](../../tree/v2-final);
[ADR 0001](docs/adr/0001-v3-scope.md) explains what changed and why.

## Contents

- [Quick start](#quick-start)
- [How it works](#how-it-works)
- [Requirements map](#requirements-map)
- [API](#api)
- [Build and test](#build-and-test)
- [Load test](#load-test)
- [Security](#security)
- [Deploying to AWS](#deploying-to-aws)
- [Limitations](#limitations)
- [Repository layout](#repository-layout)

## Quick start

Requirements: Docker with Compose v2. Nothing else; the build runs inside Docker.

```bash
git clone https://github.com/carlostajandev/event-management-platform.git
cd event-management-platform
docker compose up -d --build --wait     # DynamoDB Local, ElasticMQ (SQS), table setup, api, worker
requests/demo.sh                        # the whole purchase flow with curl
```

| Service | URL |
|---|---|
| API (`api` role) | http://localhost:8080 |
| `worker` role (health only) | http://localhost:8081 |
| DynamoDB Local | http://localhost:8000 |
| ElasticMQ (SQS API · UI) | http://localhost:9324 · http://localhost:9325 |

```bash
curl localhost:8080/actuator/health/readiness   # {"status":"UP"} once the app can serve traffic
curl localhost:8080/actuator/health             # DOWN if a DynamoDB table is missing or not ACTIVE
curl localhost:8080/actuator/info               # {"role":"api"}   (8081 → {"role":"worker"})
docker compose down                             # DynamoDB Local runs in memory: data is gone
```

The AWS credentials in `docker-compose.yml` are dummy values; the local emulators do not check them.
For Postman, import [`requests/ticketing.postman_collection.json`](requests/ticketing.postman_collection.json)
and run it top to bottom: every request carries a test (17 checks).

## How it works

```
POST /orders ──► api ──► one DynamoDB transaction ──────────────────────► 202 {orderId, RESERVED, expiresAt}
                         inventory  available -= n   IF available >= n
                         order      RESERVED, expiresAt = now + 10 min
                         idempotency key              IF new
                         audit + outbox message
                                │
                     worker: outbox ──► SQS ──► worker: RESERVED → PENDING_CONFIRMATION  IF RESERVED AND not expired
                                                          │
POST /orders/{id}/confirm ──► api: PENDING_CONFIRMATION → SOLD  IF still PENDING_CONFIRMATION AND not expired
worker, every 5 s: expired RESERVED / PENDING_CONFIRMATION → AVAILABLE, tickets back to the pool
```

- **Every state change is one `TransactWriteItems`:** the order (conditional on the state it expects), the
  inventory counters and the audit record commit together or not at all. No locks, no read-then-write:
  races are settled by DynamoDB conditions, so exactly one writer wins.
- **The hold happens in the request**, so the buyer gets real tickets immediately; the queue still carries
  the asynchronous processing ([ADR 0002](docs/adr/0002-purchase-flow.md)).
- **Outbox:** the message is written in the order's own transaction, so an order can never be committed
  without its message.
- **Idempotency:** every write needs an `Idempotency-Key`; a retry returns the original result.

Diagrams (context, containers, layers, state machine, sequences, data model):
[`docs/architecture.md`](docs/architecture.md).

| ADR | Decision |
|---|---|
| [0001](docs/adr/0001-v3-scope.md) | From four services sharing tables to one deployable with two roles |
| [0002](docs/adr/0002-purchase-flow.md) | Hold tickets and enqueue in one transaction; the consumer validates the hold |
| [0003](docs/adr/0003-authentication-out-of-scope.md) | Authentication out of scope, behind one interface |
| [0004](docs/adr/0004-no-circuit-breaker-on-dynamodb.md) | No circuit breaker around DynamoDB; backpressure at the edge instead |
| [0005](docs/adr/0005-local-aws-emulators.md) | DynamoDB Local and ElasticMQ instead of LocalStack |

## Requirements map

| Requirement | Implementation | Verified by |
|---|---|---|
| RF1 Create and query events with an inventory | `POST/GET /events`, inventory item with one counter per state | `EventUseCasesTest`, `DynamoDbEventRepositoryIntegrationTest` |
| RF2 Hold tickets for at most 10 minutes | `expiresAt = created + 10 min`, one deadline for the whole hold | `OrderTest`, `PurchaseFlowIntegrationTest` |
| RF3 Enqueue immediately, return an order id, process asynchronously | `202` with `orderId`; outbox → SQS → `PlacedOrderListener` | `PurchaseFlowIntegrationTest` |
| RF4 Order status at any time | `GET /orders/{id}` (owner only) | `OrderUseCasesTest`, `ApiWebTest` |
| RF5 No overselling under concurrency | `available >= :n` condition in the transaction | 200 concurrent buyers for 50 tickets → exactly 50 (`DynamoDbOrderRepositoryIntegrationTest`); k6 sell-out |
| RF6 Release expired holds periodically | `ExpiredHoldsJob` over the sparse `holds-by-expiry` index | `WorkerUseCasesTest`, `PurchaseFlowIntegrationTest` |
| RF7 Real-time availability | `GET /events/{id}/availability`, counts sold and held tickets | `ApiFlowIntegrationTest` |
| States AVAILABLE · RESERVED · PENDING_CONFIRMATION · SOLD · COMPLIMENTARY | `TicketState`; SOLD and COMPLIMENTARY final; COMPLIMENTARY at zero amount | `OrderTest`, `TicketStateTest` |
| Atomic and auditable transitions | One transaction per transition, audit item in it | Repository ITs |
| Fully reactive API (`Mono`/`Flux`) | WebFlux + AWS SDK async client; nothing blocks the event loop | `ApiWebTest` |
| Reactive error handling with retry | Retry only transient cancellations, jittered backoff; RFC 9457 errors | `TransactionWriterTest` |
| SQS at least once | Delete only after processing; idempotent consumer; DLQ after 3 receives | `PurchaseFlowIntegrationTest` (duplicate delivery) |
| Conditional writes | On the invariant (`available >= :n`, `status = :from`) | Repository ITs |
| Clean Architecture | `domain` → `application` → `infrastructure` | `ArchitectureTest` (ArchUnit) |
| ≥ 90% coverage | 98.1% lines, 91.2% branches, gate at 90% | `./mvnw verify` |
| docker-compose with all dependencies | DynamoDB Local, ElasticMQ, table setup, api, worker | CI smoke job |
| Request collection | Postman collection + `requests/demo.sh` | Run in this repository |

## API

Base path `/api/v1`. JSON in and out. Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem
documents with a stable `code`.

| Method | Path | Headers | Answer |
|---|---|---|---|
| `POST` | `/events` | `X-User-Role: admin`, `Idempotency-Key` | `201` event |
| `GET` | `/events?limit=20` | — | `200` upcoming events by date |
| `GET` | `/events/{id}` | — | `200` event |
| `GET` | `/events/{id}/availability` | — | `200` counters per state |
| `POST` | `/events/{id}/complimentary` | `X-User-Role: admin`, `Idempotency-Key` | `201` order in `COMPLIMENTARY` |
| `POST` | `/orders` | `X-User-Id`, `Idempotency-Key` | `202` order in `RESERVED` |
| `GET` | `/orders/{id}` | `X-User-Id` | `200` order (`404` if it belongs to someone else) |
| `POST` | `/orders/{id}/confirm` | `X-User-Id` | `200` order in `SOLD` (simulated payment) |

`X-User-Id` and `X-User-Role` stand in for authentication, which is out of scope
([ADR 0003](docs/adr/0003-authentication-out-of-scope.md)).

### Examples

Responses below were captured from the running stack.

```bash
curl -X POST localhost:8080/api/v1/events \
  -H 'Content-Type: application/json' -H 'X-User-Role: admin' -H 'Idempotency-Key: create-rock-fest' \
  -d '{"name":"Rock Fest","venue":"Movistar Arena","date":"2026-12-15T20:00:00Z",
       "capacity":3,"ticketPrice":150000,"currency":"COP"}'
```
```json
HTTP/1.1 201 Created
Location: /api/v1/events/60551928-f639-4bf8-9660-2471204b0610

{"id":"60551928-f639-4bf8-9660-2471204b0610","name":"Rock Fest","venue":"Movistar Arena",
 "date":"2026-12-15T20:00:00Z","capacity":3,"ticketPrice":150000.00,"currency":"COP",
 "createdAt":"2026-10-28T23:38:52.602Z"}
```

```bash
curl -X POST localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' -H 'X-User-Id: ana' -H 'Idempotency-Key: ana-order-1' \
  -d '{"eventId":"60551928-f639-4bf8-9660-2471204b0610","quantity":2}'
```
```json
HTTP/1.1 202 Accepted
Location: /api/v1/orders/68df34f2-a7b5-48e3-993a-cc8342c9ab6a

{"orderId":"68df34f2-a7b5-48e3-993a-cc8342c9ab6a","eventId":"60551928-f639-4bf8-9660-2471204b0610",
 "userId":"ana","quantity":2,"totalAmount":300000.00,"currency":"COP","status":"RESERVED",
 "expiresAt":"2026-10-28T23:48:52.937Z","createdAt":"2026-10-28T23:38:52.937Z","updatedAt":"2026-10-28T23:38:52.937Z"}
```

Sending the same request again with the same key returns the same `orderId`. Moments later the worker has
processed it:

```bash
curl localhost:8080/api/v1/orders/68df34f2-a7b5-48e3-993a-cc8342c9ab6a -H 'X-User-Id: ana'     # "status":"PENDING_CONFIRMATION"
curl -X POST localhost:8080/api/v1/orders/68df34f2-a7b5-48e3-993a-cc8342c9ab6a/confirm -H 'X-User-Id: ana'   # "status":"SOLD"
curl localhost:8080/api/v1/events/60551928-f639-4bf8-9660-2471204b0610/availability
```
```json
{"eventId":"60551928-f639-4bf8-9660-2471204b0610","total":3,"available":0,"reserved":0,
 "pendingConfirmation":0,"sold":2,"complimentary":1}
```

An error:

```json
HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type":"about:blank","title":"Conflict","status":409,
 "detail":"Event 60551928-f639-4bf8-9660-2471204b0610 has 1 tickets available, 2 requested",
 "instance":"/api/v1/orders","code":"SOLD_OUT"}
```

### Error codes

| Status | `code` | Meaning | Client should |
|---|---|---|---|
| 400 | `BAD_REQUEST` | Malformed JSON, missing header, invalid field (`errors` lists the fields) | Fix the request |
| 401 | `CALLER_NOT_IDENTIFIED` | No `X-User-Id` | Send it |
| 403 | `FORBIDDEN` | Admin operation without the admin role | — |
| 404 | `NOT_FOUND` | Unknown event or order, or someone else's order | — |
| 409 | `SOLD_OUT` | Not enough tickets | Pick fewer or another event |
| 409 | `INVALID_STATE` | Transition not allowed from the current state (e.g. confirming while still `RESERVED`) | Poll the order and retry |
| 409 | `HOLD_EXPIRED` | The 10-minute hold ran out | Start a new order |
| 409 | `STATE_CHANGED` | Another writer changed the order first | Read the order |
| 409 | `DUPLICATE_REQUEST` | Same key in flight and its record vanished | Retry with the same key |
| 422 | `VALIDATION_FAILED` | Domain rule broken (dates, lengths, decimals) | Fix the request |
| 422 | `IDEMPOTENCY_KEY_REUSED` | Same key, different body | Use a new key for a new request |
| 429 | `RATE_LIMITED` | Too many requests from this user or address | Wait `Retry-After` |
| 503 | `OVERLOADED` | Instance at capacity; **nothing was written** | Retry after `Retry-After` |
| 503 | `CONTENTION` | Hot inventory item still contended after retries; nothing written | Retry after `Retry-After` |
| 503 | `TIMEOUT` | DynamoDB did not answer in time; **the write may have committed** | Retry with the **same** key: you get the original result |

## Build and test

Requirements: JDK 25 (`sdk env install` picks the version in `.sdkmanrc`) and Docker (Testcontainers).

```bash
./mvnw verify    # 172 tests + 90% line coverage gate; report in target/site/jacoco/index.html
```

| Kind | Examples |
|---|---|
| Domain | `OrderTest`, `InventoryTest`, `MoneyTest`: transitions, deadlines, invariants |
| Use cases with mocked ports | `OrderUseCasesTest`, `WorkerUseCasesTest`, `IdempotentReplayTest` (Mockito + StepVerifier) |
| WebFlux slice | `ApiWebTest` (WebTestClient), `RequestProtectionFilterTest` |
| Repositories against DynamoDB Local | Concurrency: 200 buyers for 50 tickets; consumer vs expiry race |
| End to end, both roles | `PurchaseFlowIntegrationTest`: queue, duplicate delivery, expiry |
| Architecture | `ArchitectureTest`: no framework imports in `domain` or `application` |

Coverage: **98.1% lines, 91.2% branches.** CI runs the same build plus a secret scan, a compose smoke test and
the Terraform checks.

## Load test

k6 against the compose stack ([`load-test/`](load-test)). Two profiles: `steady` (40 orders/s) and `sellout`
(300 orders/s for 1,000 tickets, far above what the local emulator absorbs).

| | Result |
|---|---|
| Overselling | None in any run: counters always add up to the capacity |
| `steady` p95 | 59 ms, 0 errors |
| `sellout` | 0 server errors, 0 datastore timeouts, all 1,000 tickets reported to their buyers, p95 96 ms |

The first sell-out run found three real problems (committed orders answered as `500`, a readiness check that
would drain the whole fleet, dropped errors), all fixed. Details:
[`load-test/RESULTS.md`](load-test/RESULTS.md).

## Security

Mapped control by control to code and tests in [`docs/security/SECURITY.md`](docs/security/SECURITY.md):

- **Secrets:** none in the repository; gitleaks over the full history in CI; tasks use IAM roles; CI uses
  GitHub OIDC (no stored AWS keys).
- **Idempotency and malicious retries:** mandatory key stored in the same transaction, scoped per caller,
  request hash → `422` on reuse; conditional transitions make duplicate messages harmless.
- **Resource abuse:** rate limits per user and address, cap on requests in flight, max 6 tickets per order,
  16 KB bodies, bounded fields; WAF in AWS.

## Deploying to AWS

[`infrastructure/terraform`](infrastructure/terraform) defines the whole environment: VPC without NAT (VPC
endpoints only), ALB + WAF, two ECS Fargate services from one image with their own least-privilege roles,
DynamoDB on demand, SQS with DLQ, alarms, and the OIDC deploy role. `dev` and `prod` share the code and differ
in tfvars and state. Decisions on networking, security, scaling, isolation and cost:
[`infrastructure/terraform/README.md`](infrastructure/terraform/README.md).

## Limitations

- **No authentication** ([ADR 0003](docs/adr/0003-authentication-out-of-scope.md)): any client can claim any
  `X-User-Id`. Ownership and per-user limits are enforced on whatever identity the header gives; a JWT
  resource server replaces one class.
- **Payment is simulated** by `POST /orders/{id}/confirm`.
- **One hot item per event:** every order updates the event's inventory item. On DynamoDB that caps one event
  at roughly 500 orders/s; sharded counters are the documented next step (ADR 0001).
- **Local numbers are not AWS numbers:** DynamoDB Local absorbs about 40 orders/s on a laptop.
- **Terraform is not applied** against a real account in this assessment: it is validated, tested with a
  mocked provider and scanned with Checkov.

## Repository layout

```
src/main/java/com/nequi/ticketing
├── domain            model, ports, sealed exceptions (no framework imports)
├── application       one class per use case
└── infrastructure    web, persistence.dynamodb, messaging.sqs, scheduling, config
src/test/java         unit, slice, integration (Testcontainers) and architecture tests
infrastructure/local  DynamoDB table setup and ElasticMQ config for compose
infrastructure/terraform  AWS deployment, per-environment tfvars, module tests
load-test             k6 script and results
requests              Postman collection and curl demo
docs                  architecture, ADRs, security
```
