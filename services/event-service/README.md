# event-service

Manages event catalog and ticket inventory. Owns the `emp-events` DynamoDB table.

**Port:** 8081

## Responsibilities

- Create, update, and query events
- Expose real-time ticket availability (atomic counter — no item-per-ticket)
- Support write sharding for high-demand events (>10K concurrent requests)

## Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/events` | Create a new event |
| `GET` | `/api/v1/events` | List all events |
| `GET` | `/api/v1/events/{eventId}` | Get event by ID |
| `GET` | `/api/v1/events/{eventId}/availability` | Get current available ticket count |
| `GET` | `/actuator/health` | Health check |
| `GET` | `/actuator/prometheus` | Prometheus metrics |

## Key Design Decisions

- **Atomic counter**: `availableCount` stored as a single attribute on the event item. `UpdateItem` with `ConditionExpression: availableCount >= :n AND version = :expected` prevents overselling without distributed locks.
- **Write sharding**: For events with extreme concurrency, 8 shard items (`EVENT#id#SHARD_0..7`) distribute the counter across DynamoDB partitions. `ThreadLocalRandom` selects the shard on write; `Flux.merge` aggregates on read.
- **Optimistic locking**: `version` attribute increments on each update. Conflicts → `ConcurrentModificationException` → Reactor `Retry.backoff(3, 100ms)` → 409 after 3 failures.

## DynamoDB Table: `emp-events`

| Key | Pattern |
|---|---|
| PK | `EVENT#{eventId}` |
| SK | `METADATA` |
| GSI1PK | `STATUS#{status}` |
| GSI1SK | `DATE#{eventDate}` |

TTL: none (events are permanent records)

## Running Locally

```bash
# From repo root — requires LocalStack running
docker compose -f infrastructure/docker-compose.yml up localstack -d
./mvnw spring-boot:run -pl services/event-service -Dspring-boot.run.profiles=local
```

## Running Tests

```bash
./mvnw test -pl shared/domain,shared/infrastructure,services/event-service
```
