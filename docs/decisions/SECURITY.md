# Security Architecture

## 1. Credential Management

### Local Development — No Real Credentials
Local credentials are fake and live in `application-local.yml`. Never committed to version control. `.gitignore` excludes `*.tfstate`, `*.tfvars` with secrets, and `application-prod.yml`.

```yaml
# application-local.yml — safe to commit (fake values)
aws:
  access-key-id: fakeMyKeyId
  secret-access-key: fakeSecretAccessKey
  dynamodb:
    endpoint: http://127.0.0.1:8000
  sqs:
    endpoint: http://127.0.0.1:4566
```

### Production — IAM Roles (no static keys)
The application runs on ECS Fargate with a **Task IAM Role**. AWS SDK v2 resolves credentials automatically from the container metadata endpoint — no access keys in code, environment variables, or configuration files.

```
ECS Task → Task IAM Role → IAM Policy (least privilege)
                ↑
         Assumed automatically by AWS SDK v2
         via container metadata endpoint
         (169.254.170.2/v2/credentials)
```

### Two Separate IAM Roles

**Task Execution Role** — used by ECS control plane (not the application):
```json
{
  "Actions": [
    "ecr:GetAuthorizationToken",
    "ecr:BatchGetImage",
    "logs:CreateLogGroup",
    "logs:PutLogEvents",
    "secretsmanager:GetSecretValue"
  ],
  "Resources": "scoped to account prefix"
}
```

**Task Role** — used by the Spring Boot application at runtime:
```json
{
  "Actions": [
    "dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem",
    "dynamodb:DeleteItem", "dynamodb:Query", "dynamodb:Scan"
  ],
  "Resources": [
    "arn:aws:dynamodb:us-east-1:ACCOUNT:table/emp-*",
    "arn:aws:dynamodb:us-east-1:ACCOUNT:table/emp-*/index/*"
  ]
}
```

**No wildcards in resources. No `*` in actions.**

---

## 2. Protection Against Common Attacks

### Malicious Retries — Idempotency Keys
`POST /api/v1/orders` requires `X-Idempotency-Key`. Without it → `400 Bad Request`.

```
Attack: Client sends POST 100 times to charge user 100 times
Defense: 
  - First call: processes and caches response (key → response in DynamoDB)
  - Calls 2-100: return cached response immediately, no processing
  - Key expires after 24h via DynamoDB TTL (no cleanup job needed)
```

```
Client ──POST (key=abc)──► API ──► DynamoDB (key not exists) ──► Process
Client ──POST (key=abc)──► API ──► DynamoDB (key exists)     ──► Return cache
Client ──POST (key=abc)──► API ──► DynamoDB (key exists)     ──► Return cache
```

### Race Conditions / Overselling
Optimistic locking via DynamoDB Conditional Writes:

```
UPDATE tickets
SET status = 'RESERVED', version = 2
WHERE ticketId = 'tkt_001' AND version = 1

→ If two requests race for the same ticket:
  Request A: version=1 → version=2 ✓ (wins)
  Request B: version=1 → ConditionalCheckFailedException ✗ (loses, gets 409)
```

No overselling is possible — the database enforces atomicity.

### Resource Exhaustion
- **Max tickets per order**: 10 (configurable via `MAX_TICKETS_PER_ORDER`)
- **Pagination max size**: 100 items per page
- **Request timeout**: Netty connection timeout 5s, idle timeout 60s
- **Rate limiting**: Resilience4j RateLimiter (100 req/s per instance)
- **API Gateway** (production): burst 1000 req/s, rate 500 req/s per client

### SQS Message Attacks — DLQ Protection
```
Malformed message → Consumer tries to process → Fails
SQS retries (up to 3x, visibility timeout 30s each)
→ After 3 failures: message moves to DLQ
→ CloudWatch alarm fires immediately
→ Team investigates without message being reprocessed
```

Consumer never crashes on bad messages — deserialization is wrapped in try/catch.

### Input Validation
All DTOs use Bean Validation annotations. Violations → `400 Bad Request` with structured error:

```java
@NotBlank String name;
@Future  Instant eventDate;
@Min(1) @Max(10) int quantity;
@NotBlank String eventId;
@NotBlank String userId;
```

Validation happens in the Handler before reaching the use case — the domain never sees invalid input.

---

## 3. Error Response Hardening

### What We NEVER expose in error responses:
- Stack traces
- DynamoDB table names
- Internal IP addresses
- AWS account IDs
- Package structure (`com.nequi.ticketing...`)
- Spring/framework version details

### What we DO return:
```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Event not found: evt_unknown",
  "path": "/api/v1/events/evt_unknown",
  "timestamp": "2027-06-01T10:00:00Z"
}
```

`GlobalErrorHandler` maps all domain exceptions before they reach the client:

| Exception | HTTP Status | Logged as |
|---|---|---|
| `EventNotFoundException` | 404 | WARN |
| `OrderNotFoundException` | 404 | WARN |
| `TicketNotAvailableException` | 409 | WARN |
| `InvalidTicketStateException` | 409 | WARN |
| `ValidationException` | 400 | WARN |
| `Unhandled exception` | 500 | ERROR |

500 errors log the full stack trace server-side but return only a generic message to the client.

---

## 4. Transport Security

### HTTPS Only
- ALB enforces HTTPS — HTTP redirects to HTTPS (301)
- TLS policy: `ELBSecurityPolicy-TLS13-1-2-2021-06` (TLS 1.2+ only)
- ACM certificate — auto-renewed, no manual certificate management

### CORS Configuration
```java
// CorsConfig.java — NOT wildcard
config.setAllowedOrigins(List.of(
    "https://ticketing.nequi.com",
    "https://app.nequi.com"
));
config.setAllowedHeaders(List.of(
    "Content-Type", "Authorization",
    "X-Correlation-Id", "X-Idempotency-Key"
));
config.setAllowCredentials(true);
```

No `*` allowed origins in production.

---

## 5. Data Encryption

| Layer | Mechanism |
|---|---|
| In transit | TLS 1.2+ (ALB → client), HTTPS (ECS → DynamoDB via VPC endpoint) |
| DynamoDB at rest | AES-256 (AWS managed keys) — enabled on all 6 tables |
| SQS at rest | KMS encryption (`alias/aws/sqs`) on both queue and DLQ |
| ECS secrets | AWS Secrets Manager — injected as env vars at task startup |
| EBS volumes | Encrypted by default in ECS Fargate |

---

## 6. Network Isolation

```
Internet → ALB (public subnet) → ECS Tasks (private subnet)
                                        │
                                        ├── DynamoDB  (VPC Gateway Endpoint)
                                        ├── SQS       (VPC Interface Endpoint)
                                        ├── ECR       (VPC Interface Endpoint)
                                        └── CloudWatch (VPC Interface Endpoint)

ECS Tasks NEVER have a public IP.
Traffic to AWS services NEVER leaves the AWS network.
Only outbound internet: NAT Gateway (for OS updates, external APIs).
```

---

## 7. Audit Trail

Every state change is recorded in `emp-audit`:

```
Entity: tkt_001
Timeline:
  2027-06-01T10:00:00Z  AVAILABLE → RESERVED    (user: usr_001, corr: abc-123)
  2027-06-01T10:00:05Z  RESERVED  → PENDING_CONF (system, corr: abc-123)
  2027-06-01T10:00:10Z  PENDING   → SOLD         (system, corr: abc-123)
```

`AuditService` absorbs failures — audit never breaks the main business flow. If DynamoDB audit write fails, a WARN is logged but the reservation succeeds.