# Security Architecture

## 1. Secure Credential Handling

### Local (development)
Local credentials are fake and live in `application-local.yml`:
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
Real credentials are never committed. `.gitignore` excludes `application-prod.yml` and `.env` files.

### Production — AWS IAM Roles (no static credentials)
In production **no access keys are used**. The application runs in ECS Fargate with a **Task IAM Role**:
```
ECS Task → Task IAM Role → IAM Policy (least privilege)
```

The IAM policy applies the principle of least privilege:
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

AWS SDK v2 resolves credentials automatically from the container metadata endpoint — no additional code required.

### Secrets Manager for Sensitive Configuration
Configuration parameters (DB URLs, payment gateway API keys) are stored in **AWS Secrets Manager** and injected as environment variables into the Task Definition via `secrets:` — never in plaintext in the code or repository.

---

## 2. Protection Against Common Attacks

### Malicious Retries — Idempotency Keys
The system requires `X-Idempotency-Key` on every `POST /api/v1/orders`. This mechanism:

- **Prevents duplicate charges**: if the client retries due to timeout, the second call returns the cached response without processing a new reservation.
- **24h TTL in DynamoDB**: keys expire automatically — no indefinite accumulation.
- **User-scoped**: in production the key is combined with `userId` so that one user's key does not interfere with another's.
```
Client ──POST (key=abc)──► API ──► DynamoDB (key not found) ──► Process
Client ──POST (key=abc)──► API ──► DynamoDB (key found)     ──► Return cache (no processing)
```

### Rate Limiting and Resource Abuse
In production this is implemented at two levels:

1. **API Gateway** — throttling per IP and per API key:
   - Burst limit: 1000 req/s
   - Rate limit: 500 req/s per client

2. **Resilience4j RateLimiter** in the service — protects DynamoDB from internal spikes.

3. **`quantity` validation** — maximum 10 tickets per order (configurable). Prevents a malicious actor from exhausting inventory in a single call.

### Input Validation and Injection Prevention
All DTOs use Bean Validation (`@NotBlank`, `@Min`, `@Max`, `@NotNull`). `OrderHandler` validates before passing to the use case. Validation errors return HTTP 400 with a structured message — no stack traces exposed.
```java
// GlobalErrorHandler maps ValidationException → 400
case "ValidationException" -> HttpStatus.BAD_REQUEST;
```

### Minimal Information Exposure in Errors
`GlobalErrorHandler` returns only `status`, `error`, `message` and `path`. It never exposes:
- Stack traces
- DynamoDB table names
- Internal dependency versions
- Server information (`Server` header removed)

### Mandatory HTTPS
In production all traffic passes through an Application Load Balancer with an ACM certificate. The ALB redirects HTTP → HTTPS. The app in Fargate only listens on the internal port — not directly exposed to the internet.

### SQS — Malicious Messages
The `SqsOrderConsumer` handles invalid messages without crashing:
- Deserialization in `try/catch` — an invalid message does not crash the consumer.
- Unprocessable message → **not deleted** → SQS retries 3 times → moves to **Dead Letter Queue**.
- DLQ with CloudWatch alarm — alerts the team when problematic messages arrive.

### Optimistic Locking — Race Condition Prevention
Every write to DynamoDB includes `ConditionExpression: version = N`. If two concurrent requests try to reserve the same ticket, only one wins. The loser receives 409 — no silent overselling occurs.

---

## 3. Additional Production Considerations

| Risk | Mitigation |
|---|---|
| Credentials exposed in logs | `@Slf4j` never logs sensitive fields. Jackson excludes password fields. |
| Unlimited DynamoDB scan | GSIs instead of full table scans. `findExpiredReservations` migrated to GSI in production. |
| SQS message flooding | Dead Letter Queue + CloudWatch alarm. Visibility timeout 30s. |
| Ticket exhaustion by bots | Rate limiting in API Gateway + CAPTCHA in frontend. |
| JWT tokens (future) | Spring Security WebFlux with token validation in reactive filter. |
| CORS | Explicit allowed origins configuration — no `*` in production. |

---

## 4. `@Profile` Pattern for AWS Credentials — Local/Production Separation

### The Problem It Solves

An AWS configuration without profile separation would lead to one of these antipatterns:

- Fake credentials (`fakeMyKeyId`) active in production → silent failure or security breach.
- Real credentials in `application.yml` → risk of accidental commit to the repository.

### Implementation in `DynamoDbConfig` and `SqsConfig`
```java
// ── Local/test profile — points to DynamoDB Local / LocalStack ───────────────
@Bean
@Profile({"local", "test"})
public DynamoDbAsyncClient dynamoDbAsyncClientLocal(AwsProperties props) {
    return DynamoDbAsyncClient.builder()
            .endpointOverride(URI.create(props.dynamodb().endpoint()))
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("fakeMyKeyId", "fakeSecretAccessKey")))
            .build();
}

// ── Prod/staging profile — ECS Task IAM Role (zero hardcoded credentials) ────
@Bean
@Profile({"prod", "staging"})
public DynamoDbAsyncClient dynamoDbAsyncClientProd(AwsProperties props) {
    return DynamoDbAsyncClient.builder()
            .region(Region.of(props.region()))
            // DefaultCredentialsProvider resolves in order:
            // 1. ECS Task IAM Role (metadata endpoint 169.254.170.2) — production
            // 2. EC2 Instance Profile — if running on EC2
            // 3. ~/.aws/credentials — developer machine
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();
}
```

### Resolution Flow in Production (ECS Fargate)
```
Application starts in Fargate (SPRING_PROFILES_ACTIVE=prod)
  → Spring activates the @Profile("prod") bean
  → DefaultCredentialsProvider calls the container metadata endpoint
  → AWS returns temporary credentials rotated automatically every hour
  → Credentials never touch the code, repository, or environment variables
```

### Why Not Use `AWS_ACCESS_KEY_ID` Environment Variables

Environment variables are read by `DefaultCredentialsProvider` and are valid for local development. However:

- In production with ECS + IAM Roles, you never need environment variables with credentials — the runtime manages them automatically.
- Environment variables with credentials in a Task Definition are visible in the AWS console to any user with `ecs:DescribeTaskDefinition` permission.
- IAM Roles have no secret to rotate manually — AWS rotates temporary credentials automatically every ~1 hour.

### Verification
```bash
# Verify that the prod profile has NO static credentials
SPRING_PROFILES_ACTIVE=prod ./mvnw spring-boot:run
# → should start using DefaultCredentialsProvider (IAM Role or ~/.aws/credentials)

# Verify that the local profile uses fake credentials
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
# → uses fakeMyKeyId → points to DynamoDB Local (http://localhost:8000)
```
