# Cloud-Native AWS Architecture

## 1. Why AWS for This Platform

| Requirement | AWS Solution | Rationale |
|---|---|---|
| High concurrency | ECS Fargate + ALB | Auto-scaling from 2 to 20 tasks in 60s |
| Financial data | DynamoDB PITR | Point-in-time recovery for 35 days |
| No overselling | DynamoDB conditional writes | Atomic operations at the database level |
| Async processing | SQS + DLQ | At-least-once delivery, failure isolation |
| Zero-downtime deploy | ECS rolling update | 50% min healthy, 200% max during deploy |
| Distributed lock | DynamoDB + ShedLock | Prevents scheduler duplication across instances |
| Observability | CloudWatch + X-Ray | Logs, metrics, and traces in one platform |

---

## 2. Production AWS Architecture

```
                     ┌─────────────────────────────────────────────┐
                     │            AWS Cloud (us-east-1)             │
                     │                                              │
  Internet ─────────►│  ┌──────────────┐   ┌───────────────────┐  │
                     │  │  Route 53    │   │   CloudFront+WAF  │  │
                     │  │  (DNS)       │──►│   Rate limiting   │  │
                     │  └──────────────┘   │   OWASP rules     │  │
                     │                     └────────┬──────────┘  │
                     │                              │              │
                     │  PUBLIC SUBNETS              │              │
                     │  ┌────────────────────────────▼──────────┐ │
                     │  │        Application Load Balancer       │ │
                     │  │   TLS 1.3 · ACM cert · HTTP→HTTPS     │ │
                     │  └──────┬────────────┬────────────┬───────┘ │
                     │         │            │            │         │
                     │  PRIVATE SUBNETS                           │
                     │  ┌──────▼──┐   ┌─────▼──┐   ┌────▼───┐   │
                     │  │Fargate  │   │Fargate │   │Fargate │   │
                     │  │AZ-1a    │   │AZ-1b   │   │AZ-1c   │   │
                     │  │Java 21  │   │Java 21 │   │Java 21 │   │
                     │  │512 CPU  │   │512 CPU │   │512 CPU │   │
                     │  │1024 MB  │   │1024 MB │   │1024 MB │   │
                     │  └────┬────┘   └────┬───┘   └────┬───┘   │
                     │       └────────────┬┘────────────┘         │
                     │                    │                        │
                     │         ┌──────────┴──────────┐            │
                     │         │                     │            │
                     │  ┌──────▼──────┐    ┌─────────▼──────┐    │
                     │  │  DynamoDB   │    │      SQS       │    │
                     │  │  6 tables   │    │  purchase-     │    │
                     │  │  PITR:on    │    │  orders + DLQ  │    │
                     │  │  SSE:on     │    │  KMS encrypted │    │
                     │  └─────────────┘    └────────────────┘    │
                     │                                            │
                     │  VPC Endpoints (no NAT cost for AWS APIs)  │
                     │  · dynamodb · sqs · ecr.api · ecr.dkr     │
                     │  · logs · secretsmanager                   │
                     └─────────────────────────────────────────────┘
```

---

## 3. Auto-Scaling Strategy

```
SCALE OUT (add tasks):
  Trigger 1: ALB RequestCountPerTarget > 1000/min
    → scale_out_cooldown = 60s (react quickly to traffic spikes)
  Trigger 2: ECS CPU > 70%
    → scale_out_cooldown = 60s

SCALE IN (remove tasks):
  scale_in_cooldown = 300s (don't remove too fast — avoid oscillation)
  min_capacity = 2 (always at least 2 tasks for HA)

EXAMPLE — Concert ticket sale event:
  T+00s: Announcement posted → traffic spike begins
  T+60s: CPU hits 80% → auto-scaling triggered
  T+120s: 4 tasks running
  T+180s: 8 tasks running
  T+300s: 15 tasks (peak)
  T+600s: Sale ends → scale-in begins
  T+900s: Back to 2 tasks (minimum)
```

---

## 4. Deployment Strategy — Zero Downtime

```
Rolling Deploy (50% min healthy, 200% max during deploy):

Before deploy:    [Task v1] [Task v1]

During deploy:    [Task v1] [Task v1] [Task v2] [Task v2]
                  ↑ ALB routes to both v1 and v2 ↑

Health check:     Task v2 must pass /actuator/health
                  (startPeriod=60s, interval=30s, threshold=2)

After drain:      [Task v2] [Task v2]
                  ALB drains v1 connections (deregistration_delay=30s)

Rollback:         If health check fails → ECS stops deployment
                  Previous task definition remains active
                  aws ecs update-service --task-definition v1 --force-new-deployment
```

---

## 5. Cost Breakdown (Production — Moderate Load)

```
Assumptions:
  · 2 ECS tasks minimum (prod), scale to 10 under load
  · 1M DynamoDB writes/month, 5M reads/month
  · 500K SQS messages/month
  · 100GB data transfer through NAT
  · 4 VPC interface endpoints (SQS, ECR x2, CloudWatch)

┌─────────────────────────────────────────────────────────────────┐
│ Resource               Config              Estimated $/month     │
├─────────────────────────────────────────────────────────────────┤
│ ECS Fargate            2× 0.5vCPU 1GB     ~$25                 │
│ DynamoDB               1M writes, 5M reads ~$8                 │
│ SQS                    500K messages       ~$0.40               │
│ ALB                    1 ALB, 10 LCU       ~$20                 │
│ NAT Gateway            100GB data          ~$14                 │
│ CloudWatch             5GB logs, alarms    ~$5                  │
│ VPC Interface Endpoints 4× endpoints       ~$30                 │
│ ACM Certificate        1 cert              FREE                 │
│ Route 53               1 hosted zone       ~$0.50               │
│ Secrets Manager        3 secrets           ~$0.60               │
├─────────────────────────────────────────────────────────────────┤
│ TOTAL                                      ~$103/month          │
└─────────────────────────────────────────────────────────────────┘

Cost optimizations applied:
  · DynamoDB PAY_PER_REQUEST: no waste on unused capacity
  · VPC endpoints for DynamoDB/SQS: ~$30/month savings vs NAT
  · Long polling on SQS (20s): ~95% reduction in empty receives
  · ECS Fargate SPOT for non-critical tasks: ~70% cost reduction
  · CloudWatch log retention: 90 days prod, 14 days dev
```

---

## 6. Disaster Recovery Strategy

### Recovery Objectives
| Scenario | RTO | RPO | Strategy |
|---|---|---|---|
| ECS task crash | < 30s | 0 | ECS restarts task automatically |
| AZ failure | < 60s | 0 | ALB reroutes to healthy AZs |
| DynamoDB table corruption | < 4h | < 1min | PITR restore to any second in 35 days |
| Region failure | < 2h | < 5min | Manual failover to us-west-2 (documented runbook) |

### DynamoDB PITR — Point-in-Time Recovery
```bash
# Restore entire table to 5 minutes ago
aws dynamodb restore-table-to-point-in-time \
  --source-table-name emp-tickets \
  --target-table-name emp-tickets-restored \
  --restore-date-time $(date -d '5 minutes ago' --iso-8601=seconds)

# Verify restoration
aws dynamodb describe-table --table-name emp-tickets-restored

# Update application to point to restored table (env var)
aws ecs update-service \
  --cluster emp-prod-cluster \
  --service emp-prod-service \
  --force-new-deployment
```

### Multi-AZ Resilience
All resources are deployed across 3 AZs:
- ECS tasks: spread across AZ-1a, AZ-1b, AZ-1c via `distinctInstance` placement constraint
- DynamoDB: natively multi-AZ (managed by AWS)
- SQS: natively multi-AZ (managed by AWS)
- ALB: spans all 3 AZs, routes only to healthy targets

---

## 7. Observability — Three Pillars

### Logs — Structured JSON via CloudWatch
Every log line emitted as structured JSON with `X-Correlation-Id` in MDC:
```json
{
  "timestamp": "2027-06-01T10:00:00.000Z",
  "level": "INFO",
  "logger": "ReserveTicketsService",
  "correlationId": "550e8400-e29b-41d4-a716-446655440000",
  "orderId": "ord_x1y2z3w4",
  "eventId": "evt_a1b2c3d4",
  "userId": "usr_001",
  "quantity": 2,
  "message": "Tickets reserved successfully"
}
```
Log retention: 90 days in production, 14 days in dev.

### Metrics — Micrometer → Prometheus → CloudWatch
Key metrics exposed at `/actuator/prometheus`:
```
http_server_requests_seconds_count{uri="/api/v1/orders",status="201"} 12450
http_server_requests_seconds_p99{uri="/api/v1/orders"} 0.087
resilience4j_circuitbreaker_state{name="dynamodb"} 0 (CLOSED=0, OPEN=2)
jvm_memory_used_bytes{area="heap"} 234567890
process_cpu_usage 0.23
```

CloudWatch Alarms:
| Alarm | Threshold | Action |
|---|---|---|
| SQS DLQ messages | > 0 | PagerDuty alert |
| P99 latency | > 500ms | Warning alert |
| Error rate | > 1% | Critical alert |
| ECS CPU | > 80% | Auto-scale out |
| Circuit breaker OPEN | State change | Warning alert |

### Traces — AWS X-Ray
`X-Correlation-Id` propagated from client → ALB → ECS → DynamoDB → SQS. Full request trace visible in X-Ray console showing:
- Total request duration
- DynamoDB call latency breakdown
- SQS publish latency
- Cold start impact

---

## 8. Security Posture

| Layer | Control |
|---|---|
| Network | VPC private subnets, SGs with minimum rules, VPC endpoints |
| Transport | TLS 1.3 only, ACM certificates, HSTS |
| Identity | IAM roles (no static keys), least privilege, no wildcards |
| Application | Input validation, CORS, idempotency, rate limiting |
| Data | AES-256 at rest (DynamoDB + SQS), KMS managed keys |
| Audit | All state changes recorded in emp-audit table |
| Incidents | CloudWatch alarms → PagerDuty → runbook |

---

## 9. AWS Well-Architected Framework Alignment

| Pillar | Implementation |
|---|---|
| **Operational Excellence** | CI/CD pipeline, IaC (Terraform), structured logging, runbooks |
| **Security** | IAM least privilege, VPC isolation, encryption everywhere, no static credentials |
| **Reliability** | Multi-AZ, auto-scaling, circuit breakers, DLQ, PITR |
| **Performance Efficiency** | WebFlux non-blocking I/O, DynamoDB PAY_PER_REQUEST, VPC endpoints |
| **Cost Optimization** | PAY_PER_REQUEST billing, Fargate Spot, long polling, VPC endpoints vs NAT |
| **Sustainability** | Right-sizing with auto-scaling (no over-provisioning), Fargate (shared infrastructure) |