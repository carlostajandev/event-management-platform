# Ticketing Platform v3

Reactive ticketing backend for high-demand ticket releases: Java 25, Spring Boot 4, WebFlux,
DynamoDB and SQS. One deployable that runs as two independently scalable roles.

> **Status:** v3 in progress on branch `v3/modular-monolith`. The previous microservices version is
> preserved under the tag [`v2-final`](../../tree/v2-final). The full README (API usage, diagrams,
> limitations) lands with the final phase.

## Architecture at a glance

| Role | Responsibility | Scales on |
|---|---|---|
| `api` | Reactive HTTP API (events, orders, availability) | Request load |
| `worker` | SQS consumer, outbox relay, expiry of holds | Queue depth |

- Clean Architecture: `domain` → `application` → `infrastructure`, enforced by an ArchUnit test.
- Every ticket state transition is a single DynamoDB `TransactWriteItems`: the state change
  (conditional on the expected state), inventory counters and the audit record commit together.
- Ticket states follow the spec: `AVAILABLE`, `RESERVED`, `PENDING_CONFIRMATION`, `SOLD`, `COMPLIMENTARY`.

Design decisions are recorded in [`docs/adr`](docs/adr):

| ADR | Decision |
|---|---|
| [0001](docs/adr/0001-v3-scope.md) | From four services sharing tables to one deployable with two roles |
| [0002](docs/adr/0002-purchase-flow.md) | Hold tickets and enqueue in one transaction; the consumer validates the hold |
| [0003](docs/adr/0003-authentication-out-of-scope.md) | Authentication out of scope, behind a port |
| [0004](docs/adr/0004-no-circuit-breaker-on-dynamodb.md) | No circuit breaker around DynamoDB |
| [0005](docs/adr/0005-local-aws-emulators.md) | DynamoDB Local and ElasticMQ instead of LocalStack |

Security controls (secrets, idempotency, malicious retries, resource abuse) are mapped to their code in
[`docs/security/SECURITY.md`](docs/security/SECURITY.md). Load test results: [`load-test/RESULTS.md`](load-test/RESULTS.md).

## Run locally

Requirements: Docker with Compose v2.

```bash
docker compose up -d --build --wait
```

| Service | URL |
|---|---|
| API role | http://localhost:8080 |
| Worker role | http://localhost:8081 |
| DynamoDB Local | http://localhost:8000 |
| ElasticMQ (SQS) | http://localhost:9324 (UI http://localhost:9325) |

```bash
curl localhost:8080/actuator/health/readiness   # {"status":"UP"} once the app can serve traffic
curl localhost:8080/actuator/health             # DOWN if a DynamoDB table is missing or not ACTIVE
curl localhost:8080/actuator/info               # {"role":"api"}
curl localhost:8081/actuator/info               # {"role":"worker"}
docker compose down                             # stops everything; DynamoDB Local data is in memory
```

The AWS credentials in `docker-compose.yml` are dummy values; the local emulators do not validate them.

## Build and test

Requirements: JDK 25 (`sdk env install` picks the version in `.sdkmanrc`).

```bash
./mvnw verify    # unit, slice and architecture tests + 90% line coverage gate (JaCoCo)
```
