# Cloud-Native AWS Architecture

## Arquitectura de producción en AWS

```
                        ┌─────────────────────────────────────────┐
                        │           Route 53 (DNS)                │
                        │   api.ticketing.nequi.com               │
                        └──────────────────┬──────────────────────┘
                                           │
                        ┌──────────────────▼──────────────────────┐
                        │     CloudFront (CDN + WAF)              │
                        │  - Rate limiting por IP                 │
                        │  - OWASP managed rules                  │
                        │  - Geo-restriction si aplica            │
                        └──────────────────┬──────────────────────┘
                                           │
                        ┌──────────────────▼──────────────────────┐
                        │   Application Load Balancer (ALB)       │
                        │   - TLS termination (ACM certificate)   │
                        │   - Target group: ECS tasks             │
                        │   - Health check: /actuator/health      │
                        └──────────┬──────────────────────────────┘
                                   │
              ┌────────────────────┼────────────────────┐
              │                    │                    │
    ┌─────────▼──────┐   ┌─────────▼──────┐   ┌────────▼───────┐
    │  ECS Fargate   │   │  ECS Fargate   │   │  ECS Fargate   │
    │  Task (AZ-1a)  │   │  Task (AZ-1b)  │   │  Task (AZ-1c)  │
    │  Java 21 App   │   │  Java 21 App   │   │  Java 21 App   │
    └────────────────┘   └────────────────┘   └────────────────┘
              │                    │                    │
              └────────────────────┼────────────────────┘
                                   │
                    ┌──────────────┴──────────────┐
                    │                             │
         ┌──────────▼──────────┐     ┌───────────▼──────────┐
         │    DynamoDB         │     │       SQS            │
         │  Global Tables      │     │  purchase-orders     │
         │  us-east-1          │     │  (+ DLQ)             │
         │  sa-east-1 (replica)│     └──────────────────────┘
         └─────────────────────┘
```

---

## Networking y seguridad (VPC)

```
VPC: 10.0.0.0/16
│
├── Public Subnets (10.0.1.0/24, 10.0.2.0/24, 10.0.3.0/24)
│   └── ALB (con Security Group: allow 443 from 0.0.0.0/0)
│
└── Private Subnets (10.0.10.0/24, 10.0.20.0/24, 10.0.30.0/24)
    └── ECS Fargate Tasks (SG: allow 8080 from ALB SG only)
        └── NAT Gateway → internet (para ECR pull, CloudWatch)
        └── VPC Endpoints → DynamoDB, SQS, Secrets Manager
                            (tráfico NO sale a internet)
```

**VPC Endpoints** son críticos para:
- Reducir costos de transferencia de datos (DynamoDB/SQS sin NAT Gateway)
- Seguridad: tráfico AWS→AWS permanece en la red privada de Amazon
- Cumplimiento: datos sensibles (órdenes) nunca atraviesan internet público

---

## IAM — Principio de mínimo privilegio

```
ECS Task Execution Role (para descargar imagen de ECR, logs a CloudWatch):
  - ecr:GetAuthorizationToken
  - ecr:BatchGetImage
  - logs:CreateLogGroup
  - logs:PutLogEvents
  - secretsmanager:GetSecretValue (para inyectar secrets al task)

ECS Task Role (permisos de la aplicación en runtime):
  - dynamodb:GetItem, PutItem, Query, Scan, UpdateItem
    Resource: arn:aws:dynamodb:*:*:table/emp-*
  - sqs:SendMessage, ReceiveMessage, DeleteMessage
    Resource: arn:aws:sqs:*:*:emp-purchase-orders
  - xray:PutTraceSegments (para trazas distribuidas)
```

---

## Observabilidad — Three Pillars

### Logs (CloudWatch Logs)
```json
{
  "timestamp": "2026-03-15T10:00:00Z",
  "level": "INFO",
  "traceId": "1-abc-123",
  "service": "event-management-platform",
  "orderId": "ord_uuid",
  "eventId": "evt_uuid",
  "userId": "usr_uuid",
  "message": "Tickets reserved",
  "duration_ms": 45
}
```
Configurado vía `logback-spring.xml` con `LogstashEncoder`.

### Métricas (CloudWatch + Prometheus)
| Métrica | Descripción | Alarma |
|---|---|---|
| `tickets.reserved` | Tickets reservados/min | — |
| `tickets.sold` | Tickets vendidos/min | — |
| `orders.failed` | Órdenes fallidas | >5/min → PagerDuty |
| `http.server.requests` | Latencia P95/P99 | P99 >500ms |
| `sqs.dlq.messages` | Mensajes en DLQ | >0 → alerta inmediata |
| `jvm.memory.used` | Heap usage | >80% → escalar |

### Trazas (AWS X-Ray)
Propagación de `traceId` en:
- Headers HTTP entrantes → WebFilter
- Llamadas a DynamoDB (SDK v2 auto-instrumentation)
- Mensajes SQS (MessageAttribute `X-Amzn-Trace-Id`)

---

## Auto-scaling

```yaml
# ECS Service Auto Scaling
Target tracking:
  - Métrica: ALBRequestCountPerTarget
  - Target: 1000 req/target
  - ScaleOut cooldown: 60s
  - ScaleIn cooldown: 300s
  
Min tasks: 2 (alta disponibilidad)
Max tasks: 20 (límite de costos)
```

Para eventos especiales (lanzamiento de venta Shakira): **pre-scaling manual** 30 minutos antes — no esperar al auto-scaling reactivo.

---

## Estimación de costos (us-east-1, carga moderada)

| Servicio | Configuración | Costo estimado/mes |
|---|---|---|
| ECS Fargate | 2 tasks × 0.5vCPU × 1GB, 730h | ~$25 |
| DynamoDB | on-demand, 1M writes + 5M reads | ~$8 |
| SQS | 1M mensajes/mes | ~$0.40 |
| ALB | 1 ALB, 10 LCU | ~$20 |
| CloudWatch | Logs 5GB, 10 alarmas | ~$5 |
| NAT Gateway | 100GB transferencia | ~$14 |
| **Total base** | | **~$72/mes** |

En pico (lanzamiento de venta): auto-scaling a 10 tasks = ~$250/mes por esos días.

---

## CI/CD Pipeline (GitHub Actions + AWS)

```
Push to main
     │
     ▼
┌─────────────┐
│  Build      │  ./mvnw verify (59 tests, JaCoCo >90%)
└─────────────┘
     │
     ▼
┌─────────────┐
│  Docker     │  Build image → push to ECR
│  Build      │
└─────────────┘
     │
     ▼
┌─────────────┐
│  Deploy     │  ECS Rolling update (min 50% healthy)
│  Staging    │  Smoke tests automáticos
└─────────────┘
     │
     ▼
┌─────────────┐
│  Deploy     │  Canary 10% → monitor 5min → 100%
│  Production │  Rollback automático si error rate >1%
└─────────────┘
```

---

## Disaster Recovery

| Escenario | RTO | RPO | Solución |
|---|---|---|---|
| Task crash | <30s | 0 | ECS restart automático, multi-AZ |
| AZ failure | <60s | 0 | ALB redirige a otras AZs, ECS en 3 AZs |
| DynamoDB outage | <5min | 0 | DynamoDB Global Tables → failover a sa-east-1 |
| Region failure | <30min | <1min | Route 53 health check → failover a región secundaria |

**Backup**: DynamoDB Point-In-Time Recovery (PITR) habilitado — restore a cualquier segundo de los últimos 35 días.
