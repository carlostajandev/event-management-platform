# Load test results

Script: [`flash-sale.js`](flash-sale.js) (k6 2.3.0). One event, buyers asking for 1 or 2 tickets each, one
in ten buyers sending the same request twice. Clients behave like a well-written client: on `503` they wait
`Retry-After` and resend with the same `Idempotency-Key`, up to 6 attempts.

**Environment:** `docker compose` on one laptop (Intel i7-10850H, 12 threads, 14 GB RAM, Docker 29.7.1).
The API, the worker, DynamoDB Local and ElasticMQ share the machine.

> These numbers measure **correctness under overload** and the behaviour of the local stack. They are not a
> capacity figure for AWS: DynamoDB Local is a single-process emulator and is the bottleneck in every run.

## How to run

k6 sends every request from one address, so start the stack with the per-address limit off:

```bash
RATE_LIMIT_IP_RPS=0 docker compose up -d --build --wait
docker run --rm --network host -v "$PWD/load-test:/scripts" grafana/k6:2.3.0 run /scripts/flash-sale.js
docker run --rm --network host -e PROFILE=steady -v "$PWD/load-test:/scripts" grafana/k6:2.3.0 run /scripts/flash-sale.js
```

## Results (2026-10-28)

### `steady`: 40 orders/s for 60 s, 100,000 tickets

| Metric | Value |
|---|---|
| Orders accepted | 2,400 (3,600 tickets) |
| `POST /orders` latency | p50 21 ms · p90 48 ms · p95 59 ms · max 219 ms |
| HTTP failures (5xx) | 0 |
| Clients that gave up | 0 |
| Retried requests answered with a different order | 0 |
| Orders left in `RESERVED` after the run | 0 (the worker kept up) |

All thresholds pass (p95 < 200 ms, p99 < 500 ms).

### `sellout`: 300 orders/s for 60 s, 1,000 tickets

The same run before and after the edge protection (rate limits and the in-flight cap,
`MAX_IN_FLIGHT_REQUESTS=16` locally):

| Metric | Before | After |
|---|---|---|
| HTTP 5xx | 0 | 0 |
| DynamoDB calls that timed out | thousands | **0** |
| Tickets held / reported to their buyer | 1,000 / 977 | 1,000 / **1,000** |
| Orders accepted | 652 | 662 |
| Answered `409 SOLD_OUT` | 6,111 | 13,712 |
| Requests turned away at the edge (`503 OVERLOADED`, nothing written) | — | 15,453 |
| Buyers that gave up after 6 attempts | 201 (outcome unknown) | 910 (certainly not written) |
| Retried requests answered with a different order | 0 | 0 |
| Latency of all requests | p95 ≈ 1.5 s | p95 96 ms |
| Final inventory | `available 0 · pendingConfirmation 1000` | `available 0 · pendingConfirmation 1000` |

- **No overselling** in either run: the counters add up to the capacity and exactly 1,000 tickets are held.
- **Before**, overload reached DynamoDB: calls timed out and 23 tickets went to orders whose buyer had been
  told `503 TIMEOUT` and gave up. The tickets were safe (the hold expires after 10 minutes, RF2), but those
  buyers never learned they had them.
- **After**, overload is refused before DynamoDB. A buyer who gives up was never written, so every held
  ticket belongs to a buyer who received `202`. All thresholds pass.

## Findings and changes made

The first run at 300 orders/s, before any of these changes, returned **7,561 `500` errors** while DynamoDB had committed **665 orders for
exactly 1,000 tickets**. Only 20 buyers were told they had tickets.

| Finding | Change |
|---|---|
| Overload reached DynamoDB and turned into timeouts whose outcome the client could not know. | Edge protection (ADR 0004): `429 RATE_LIMITED` per user and per address, and `503 OVERLOADED` above a cap on requests in flight. Both are answered before any write. |
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

## Sizing the in-flight cap

The cap follows Little's law: requests in flight = throughput x latency. DynamoDB Local sustains about 40
transactions/s, and queueing must stay below the 500 ms attempt timeout, so 16 in flight is the local value
(`docker-compose.yml`). On DynamoDB, at about 30 ms per transaction, the default of 64 per instance allows
about 2,000 requests/s per instance; the per-event ceiling above is reached first.
