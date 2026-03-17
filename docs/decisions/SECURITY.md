# Security Architecture — Microservices v2

## 1. Credential Handling

### Local development — Profile "local"
Fake credentials hardcoded in `DynamoDbConfig` and `SqsConfig` with `@Profile("local","test")`:
```java
StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
```
Points to LocalStack at `http://localhost:4566`. Safe to commit — never reaches AWS.

### Production — Profile "prod" / "staging"
```java
@Bean
@Profile({"prod", "staging"})
public DynamoDbAsyncClient dynamoDbAsyncClientProd() {
    return DynamoDbAsyncClient.builder()
        .region(Region.of(region))
        .credentialsProvider(DefaultCredentialsProvider.create()) // ECS Task IAM Role
        .build();
}
```
`DefaultCredentialsProvider` reads from the ECS container metadata endpoint automatically.
**Zero static credentials in code, config files, or environment variables.**

### Resolution chain in ECS Fargate
```
Application starts (SPRING_PROFILES_ACTIVE=prod)
  → DefaultCredentialsProvider polls 169.254.170.2 (IMDS)
  → AWS returns temporary credentials for the ECS Task IAM Role
  → Credentials rotated automatically every ~1 hour
  → No secret to manage, no rotation logic needed
```

---

## 2. IAM Least Privilege (Terraform managed)

Each ECS task role has only the permissions it needs — no wildcards:

```hcl
# infrastructure/terraform/modules/iam/main.tf
statement {
  actions = [
    "dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem",
    "dynamodb:DeleteItem", "dynamodb:Query", "dynamodb:TransactWriteItems"
  ]
  resources = [
    "arn:aws:dynamodb:*:*:table/emp-*",
    "arn:aws:dynamodb:*:*:table/emp-*/index/*"
  ]
}
statement {
  actions   = ["sqs:SendMessage", "sqs:ReceiveMessage", "sqs:DeleteMessage",
               "sqs:GetQueueAttributes", "sqs:GetQueueUrl"]
  resources = ["arn:aws:sqs:*:*:emp-purchase-orders*"]
}
```

---

## 3. Input Validation

All request DTOs use Bean Validation. `OrderHandler` calls `validator.validate()` before passing to use case:

```java
// CreateOrderRequest.java
public record CreateOrderRequest(
    @NotBlank(message = "reservationId is required") String reservationId,
    @NotBlank(message = "userId is required") String userId
) {}

// ReserveTicketsRequest.java
public record ReserveTicketsRequest(
    @NotBlank String eventId,
    @NotBlank String userId,
    @Min(1) @Max(10) int seatsCount   // max 10 prevents inventory exhaustion attack
) {}
```

Validation errors → HTTP 400 with structured `ErrorResponse`. Stack traces never exposed.

---

## 4. Idempotency — Prevents Duplicate Charges

`X-Idempotency-Key` header required on `POST /api/v1/orders`:

```
Client → POST /orders (key=uuid-A) → Process → store key with 24h TTL
Client → POST /orders (key=uuid-A) → Return cached response (no charge)
Client → POST /orders (key=uuid-B) → New request → process normally
```

The key is stored in `emp-idempotency-keys` inside the same `TransactWriteItems` as the order — atomically. The cached response is the full `OrderResponse` serialized as JSON.

---

## 5. Error Response — Minimal Information Exposure

`GlobalErrorHandler` returns only:
```json
{
  "status": 409,
  "error": "Conflict",
  "message": "Tickets not available for event evt_123: requested=2, available=0",
  "traceId": "550e8400-e29b-41d4-a716-446655440000",
  "timestamp": "2026-03-16T10:00:00Z"
}
```
Never exposed: stack traces, DynamoDB table names, internal class names, server headers.

---

## 6. SQS Security

- `@SqsListener(acknowledgementMode = ON_SUCCESS)` — message not deleted if processing fails
- Dead Letter Queue after 3 failures — malformed messages don't loop forever
- Visibility timeout 30s — longer than max processing time to prevent parallel processing
- KMS encryption at rest (configured in Terraform `kms_master_key_id = "alias/aws/sqs"`)

---

## 7. Network Security

| Layer | Control |
|---|---|
| VPC | Private subnets for ECS tasks — not internet-accessible |
| ALB | Public subnets only — only entry point from internet |
| Security Groups | ALB SG: 443+80 inbound; ECS SG: only from ALB SG |
| TLS | ACM certificate on ALB, HTTP→HTTPS redirect |
| VPC Endpoints | DynamoDB, SQS, ECR, CloudWatch — traffic stays inside AWS network |

---

## 8. Secrets — Not In Code

| What | How |
|---|---|
| AWS credentials | ECS Task IAM Role — no secrets |
| LocalStack fake credentials | Hardcoded `"test"/"test"` — intentional, not real |
| Sensitive app config | AWS Secrets Manager (documented, not implemented in this demo) |
| `.gitignore` | Excludes `.env`, `application-prod.yml`, `*.pem`, `*.key` |

```yaml
# .gitignore — already present
*.env
application-prod.yml
application-secret.yml
*.pem
*.key
```

---

## 9. Remaining Security Gaps (Known)

| Gap | Priority | Solution |
|---|---|---|
| No authentication | High | Spring Security WebFlux + JWT + Cognito |
| No rate limiting | High | API Gateway throttling or WebFlux RateLimiter |
| CORS not configured | Medium | `WebFluxConfigurer` with explicit allowed origins |
| No WAF rules | Medium | AWS WAF on ALB (SQL injection, OWASP Top 10) |
| No secret rotation | Low | AWS Secrets Manager with Lambda rotation for future app secrets |
