# Load test results

Script: [`flash-sale.js`](flash-sale.js) (k6 2.3.0). One event, buyers asking for 1 or 2 tickets each, one
in ten buyers sending the same request twice. Clients behave like a well-written client: on `503` they wait
`Retry-After` and resend with the same `Idempotency-Key`, up to 6 attempts.

**Environment:** `docker compose` on one laptop (Intel i7-10850H, 12 threads, 14 GB RAM, Docker 29.7.1).
The API, the worker, DynamoDB Local and ElasticMQ share the machine.

> These numbers measure **correctness under overload** and the behaviour of the local stack. They are not a
> capacity figure for AWS: DynamoDB Local is a single-process emulator and is the bottleneck in every run.

## How to run

```bash
docker compose up -d --build --wait
docker run --rm --network host -v "$PWD/load-test:/scripts" grafana/k6:2.3.0 run /scripts/flash-sale.js
docker run --rm --network host -e PROFILE=steady -v "$PWD/load-test:/scripts" grafana/k6:2.3.0 run /scripts/flash-sale.js
```

## Results (2026-10-28)

### `steady`: 40 orders/s for 60 s, 100,000 tickets

| Metric | Value |
|---|---|
| Orders accepted | 2,401 (3,601 tickets) |
| `POST /orders` latency | p50 16 ms · p90 47 ms · p95 63 ms · max 382 ms |
| HTTP failures (5xx) | 0 |
| Clients that gave up | 0 |
| Retried requests answered with a different order | 0 |
| Orders left in `RESERVED` after the run | 0 (the worker kept up) |

All thresholds pass (p95 < 200 ms, p99 < 500 ms).

### `sellout`: 300 orders/s for 60 s, 1,000 tickets

| Metric | Value |
|---|---|
| Requests | 15,027 · 0 HTTP 5xx |
| Orders accepted | 652 (977 tickets reported to buyers) |
| Answered `409 SOLD_OUT` | 6,111 |
| Client retries after `503` | 8,181 |
| Buyers that gave up after 6 attempts | 201 |
| Retried requests answered with a different order | 0 |
| Final inventory | `available 0 · pendingConfirmation 1000 · reserved 0` |

- **No overselling:** the counters add up to the capacity and exactly 1,000 tickets are held.
- **Every ticket was taken:** `available` reaches 0.
- **Gap:** 23 of the 1,000 held tickets belong to orders whose buyer gave up before seeing the `202`. Those
  orders were committed while the client was being told `503 TIMEOUT`. Nothing is lost: the hold expires after
  10 minutes and the tickets go back to the pool (RF2). The `tickets_held == capacity` threshold is kept strict
  so this gap stays visible.

## Findings and changes made

The first run at 300 orders/s returned **7,561 `500` errors** while DynamoDB had committed **665 orders for
exactly 1,000 tickets**. Only 20 buyers were told they had tickets.

| Finding | Change |
|---|---|
| A DynamoDB call that exceeds the SDK timeouts may still commit. Answering `500` hid the order from its buyer. | SDK client errors (`SdkClientException`: timeouts, aborted connections) map to `503 TIMEOUT` with `Retry-After`, telling the client to retry with the same `Idempotency-Key`. The retry returns the stored order instead of writing again. |
| The readiness probe called `DescribeTable` on every table. Under load it failed, which in AWS would take every task out of the load balancer at the same time. | Readiness is shallow (`readinessState` only). DynamoDB status stays in `/actuator/health` for monitoring. |
| A failed `DescribeTable` cancelled the other four and their errors were dropped (`onErrorDropped`). | The health check delays errors until every call completes. |

## Local ceiling

DynamoDB Local absorbs about **40–45 orders/s** on this machine. Each order is one `TransactWriteItems` with
five items, plus the worker's transition and outbox writes. Above that rate SDK attempts start hitting the
500 ms attempt timeout:

| Rate | p95 `POST /orders` | Buyers that gave up |
|---|---|---|
| 40/s | 63 ms | 0 |
| 50/s | 52 ms | 0 |
| 100/s | 1.88 s | 236 |
| 300/s | timeouts, buyers retrying | 201 |

## Expected ceiling on AWS (not measured)

On DynamoDB the hot spot is the event's inventory item. Every order updates it inside a transaction, and a
transactional write costs two write units per item. A single item supports about 1,000 write units per second,
so one event tops out at roughly **500 orders/s** before throttling. That is well above the local ceiling, and
per event, not per system. If one event needs more, the inventory item can be split into shards (ADR 0001).
This estimate gets measured once the Terraform stack exists.

## Next

Phase 6 adds backpressure at the edge (ADR 0004): rate limiting and a cap on in-flight writes, so overload is
answered with a fast `429`/`503` before reaching DynamoDB instead of a timeout whose outcome is unknown. This
test is re-run after that change; the target is no buyer giving up in `sellout`.
