# Cloud-Native AWS Architecture — Microservices v2

## 1. Why Each AWS Service

| Requirement | AWS Solution | Rationale |
|---|---|---|
| No overselling | DynamoDB conditional writes | Atomic at DB level — no distributed lock, no SPOF |
| Zero zombie orders | DynamoDB TransactWriteItems | Outbox + order atomic — if SQS fails, poller retries |
| Reservation expiry | DynamoDB TTL + Streams | O(1), zero compute, scales to millions free |
| At-least-once delivery | SQS Standard + DLQ | 3 retries then DLQ, consumer is idempotent |
| High concurrency | ECS Fargate auto-scaling | Scale 2→20 tasks in 60s based on CPU/queue depth |
| Zero-downtime deploy | ECS rolling update | 50% min healthy, 200% max during deploy |
| Financial data integrity | DynamoDB PITR | Point-in-time recovery for 35 days |
| No static credentials | ECS Task IAM Role | DefaultCredentialsProvider — zero secret management |
| Observability | CloudWatch + Prometheus | Structured JSON + Micrometer metrics |
| IaC | Terraform | 5 modules: VPC, DynamoDB, SQS, IAM, ECS |

---

## 2. Production Architecture

```
Internet
  │
  ▼
Route 53 (DNS)
  │
  ▼
CloudFront + WAF (rate limiting, OWASP rules)
  │
  ▼ HTTPS:443
Application Load Balancer (public subnets, TLS 1.3, ACM cert)
  │           │           │
  ▼           ▼           ▼
[event-svc] [reservation-svc] [order-svc]   ← ECS Fargate, private subnets
  :8081        :8082           :8083

[consumer-svc :8084]                        ← reads SQS, no ALB needed
  │
  ├── @SqsListener emp-purchase-orders
  └── OutboxPoller → emp-outbox GSI

All services → DynamoDB (6 tables, 2 AZs, PITR, SSE)
order-svc   → emp-outbox (write only)
consumer-svc → emp-outbox (read + mark published)
consumer-svc → emp-purchase-orders (SQS)

emp-purchase-orders → [3 failures] → emp-purchase-orders-dlq

CloudWatch Logs ← all 4 services (structured JSON, traceId)
Prometheus      ← /actuator/prometheus from all 4 services
Grafana         ← Prometheus datasource
```

---

## 3. Auto-Scaling Strategy

```
SCALE OUT triggers (any of):
  CPU > 70%                         → scale_out_cooldown = 60s
  ALB requests > 1000/min/target    → scale_out_cooldown = 60s
  SQS ApproximateNumberOfMessages > 100  → scale consumer-service

SCALE IN:
  scale_in_cooldown = 300s (avoid oscillation)
  min_capacity = 2                  (always HA across 2 AZs)

Concert sale scenario:
  T+0s:   Announcement → spike begins
  T+60s:  CPU hits 80% → scale-out triggered
  T+120s: 4 tasks → 8 tasks → 15 tasks (peak)
  T+600s: Sale ends → scale-in begins
  T+900s: Back to 2 tasks
```

---

## 4. Deployment — Zero Downtime

```
Rolling Deploy:
  Before:   [v1] [v1]
  During:   [v1] [v1] [v2] [v2]  ← ALB routes to both
  Check:    v2 must pass /actuator/health (startPeriod=60s)
  After:    [v2] [v2]  (v1 drained with 30s delay)

Rollback:
  If health check fails → ECS stops deployment automatically
  Previous task definition stays active
  aws ecs update-service --task-definition {prev-arn} --force-new-deployment
```

---

## 5. Cost Breakdown (staging — 2 tasks per service)

| Resource | Config | $/month |
|---|---|---|
| ECS Fargate (8 tasks × 0.5vCPU/1GB) | always-on | ~$65 |
| DynamoDB (6 tables, PAY_PER_REQUEST) | light load | ~$5 |
| SQS (2 queues) | light load | ~$1 |
| ALB (1 instance) | | ~$20 |
| NAT Gateway (1 AZ) | | ~$35 |
| CloudWatch (logs + metrics) | | ~$5 |
| ECR (4 repos) | storage | ~$2 |
| **Total** | | **~$133/month** |

**Cost optimizations applied:**
- DynamoDB PAY_PER_REQUEST — no capacity waste
- SQS long polling (20s) — ~95% reduction in empty receives
- VPC endpoints for DynamoDB/SQS — avoids NAT Gateway charges for AWS API calls

---

## 6. Disaster Recovery

| Scenario | RTO | RPO | Strategy |
|---|---|---|---|
| ECS task crash | <30s | 0 | ECS restarts task automatically |
| AZ failure | <60s | 0 | ALB reroutes to healthy AZs |
| DynamoDB corruption | <4h | <1min | PITR restore to any second in 35 days |
| Region failure | <2h | <5min | Manual failover to sa-east-1 (runbook) |

```bash
# PITR restore example
aws dynamodb restore-table-to-point-in-time \
  --source-table-name emp-reservations \
  --target-table-name emp-reservations-restored \
  --restore-date-time $(date -d '5 minutes ago' --iso-8601=seconds)
```

---

## 7. Observability (Three Pillars)

### Logs — Structured JSON via logstash-logback-encoder
```json
{
  "timestamp": "2026-03-16T10:00:00Z",
  "level": "INFO",
  "service": "reservation-service",
  "traceId": "550e8400-e29b-41d4-a716-446655440000",
  "eventId": "evt_123",
  "userId": "u_789",
  "message": "Tickets reserved successfully"
}
```
MDC populated by `CorrelationIdFilter` from `X-Correlation-Id` header.

### Metrics — Micrometer → Prometheus → Grafana
```
tickets.reserved.total{eventId}         Counter
tickets.sold.total                      Counter
orders.created.total                    Counter
orders.processed.total                  Counter
reservation.expired.released            Counter
event.available_tickets{eventId}        Gauge
order.processing.duration               Timer
```
Scraped by Prometheus from `/actuator/prometheus` every 15s.

### CloudWatch Alarms
| Alarm | Threshold | Action |
|---|---|---|
| DLQ messages | > 0 | PagerDuty alert |
| P99 latency | > 500ms | Warning alert |
| Error rate | > 1% | Critical alert |
| Circuit breaker OPEN | State change | Warning alert |

---

## 8. AWS Well-Architected Alignment

| Pillar | Implementation |
|---|---|
| **Operational Excellence** | CI/CD, Terraform IaC, structured logging, runbooks |
| **Security** | IAM least privilege, VPC isolation, encryption at rest, no static credentials |
| **Reliability** | Multi-AZ, auto-scaling, circuit breakers (Resilience4j), DLQ, PITR |
| **Performance** | WebFlux non-blocking I/O, DynamoDB PAY_PER_REQUEST, VPC endpoints |
| **Cost Optimization** | PAY_PER_REQUEST, long polling, VPC endpoints vs NAT |
| **Sustainability** | Right-sizing with auto-scaling, Fargate (shared infrastructure) |
