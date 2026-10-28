# ADR 0005 — Local AWS emulators: DynamoDB Local and ElasticMQ

- **Status:** Accepted
- **Date:** 2026-10-28

## Context

v2 used LocalStack 3.6 for DynamoDB and SQS. Current LocalStack images (`2026.x`) refuse to start
without a license token (`License activation failed ... set the LOCALSTACK_AUTH_TOKEN variable`).
Pinning an old free image would mean running an unmaintained emulator. The spec allows
"DynamoDB Local or another persistence solution" and "LocalStack or any other solution" for the queue.

## Decision

| Need | Emulator | Image |
|---|---|---|
| DynamoDB | DynamoDB Local (official AWS emulator) | `amazon/dynamodb-local:3.3.2` |
| SQS | ElasticMQ (SQS-compatible) | `softwaremill/elasticmq-native:1.7.1` |
| Table creation | AWS CLI one-shot container | `amazon/aws-cli:2.39.1` |

Verified before adopting:

- `TransactWriteItems` with `ConditionExpression`, returning per-item cancellation reasons
  (`[ConditionalCheckFailed, ...]`), which the purchase flow relies on (ADR 0002).
- TTL configuration accepted.
- SQS redrive: a message received more than `maxReceiveCount` times moves to the DLQ.

Queues are declared in `infrastructure/local/elasticmq.conf`; tables in
`infrastructure/local/dynamodb-init.sh`. Both mirror the Terraform definitions.

## Consequences

- No account or token needed to run the project; images are small and start in seconds.
- Credentials in `docker-compose.yml` are dummy values the emulators do not validate.
- **DynamoDB Local does not delete items by TTL.** Correctness never depends on TTL: expired holds are
  released by the expiry job (RF6); TTL only cleans up idempotency keys and published outbox items.
- **Neither emulator reproduces AWS throttling or partition limits.** Local load tests prove
  correctness under concurrency (no oversell, idempotent retries), not absolute capacity.
- DynamoDB Local runs in memory: `docker compose down` resets all data.
- Integration tests use the same images through Testcontainers.
