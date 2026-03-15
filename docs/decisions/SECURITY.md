# Security Architecture

## 1. Manejo seguro de credenciales

### Local (desarrollo)
Las credenciales locales son ficticias y viven en `application-local.yml`:
```yaml
aws:
  access-key-id: fakeMyKeyId
  secret-access-key: fakeSecretAccessKey
```
Nunca se commitean credenciales reales. El `.gitignore` excluye `application-prod.yml` y archivos `.env`.

### Producción — AWS IAM Roles (no credenciales estáticas)
En producción **no se usan access keys**. La aplicación corre en ECS Fargate con un **Task IAM Role**:

```
ECS Task → Task IAM Role → IAM Policy (mínimos privilegios)
```

La política de IAM aplica principio de mínimo privilegio:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "dynamodb:GetItem",
        "dynamodb:PutItem",
        "dynamodb:Query",
        "dynamodb:Scan"
      ],
      "Resource": [
        "arn:aws:dynamodb:us-east-1:ACCOUNT:table/emp-*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "sqs:SendMessage",
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage"
      ],
      "Resource": "arn:aws:sqs:us-east-1:ACCOUNT:emp-purchase-orders"
    }
  ]
}
```

AWS SDK v2 resuelve las credenciales automáticamente desde el metadata endpoint del contenedor — sin código adicional.

### Secrets Manager para configuración sensible
Parámetros de configuración (URLs de BD, API keys de pasarela de pago) se almacenan en **AWS Secrets Manager** y se inyectan como variables de entorno en el Task Definition vía `secrets:` — nunca en texto plano en el código o en el repositorio.

---

## 2. Protección contra ataques comunes

### Reintentos maliciosos — Idempotency Keys
El sistema exige `X-Idempotency-Key` en cada `POST /api/v1/orders`. Este mecanismo:

- **Previene cargos duplicados**: si el cliente reintenta por timeout, la segunda llamada devuelve la respuesta cacheada sin procesar una nueva reserva.
- **TTL de 24h en DynamoDB**: las keys expiran automáticamente — sin acumulación indefinida.
- **Scope por usuario**: en producción el key se combina con el `userId` para que una key de un usuario no interfiera con la de otro.

```
Cliente ──POST (key=abc)──► API ──► DynamoDB (key no existe) ──► Procesa
Cliente ──POST (key=abc)──► API ──► DynamoDB (key existe)    ──► Devuelve cache (no procesa)
```

### Rate Limiting y abuso de recursos
En producción se implementa a dos niveles:

1. **API Gateway** — throttling por IP y por API key:
   - Burst limit: 1000 req/s
   - Rate limit: 500 req/s por cliente

2. **Resilience4j RateLimiter** en el servicio — protege DynamoDB de picos internos.

3. **Validación de `quantity`** — máximo 10 tickets por orden (configurable). Previene que un actor malicioso agote el inventario en una sola llamada.

### Inyección y validación de inputs
Todos los DTOs usan Bean Validation (`@NotBlank`, `@Min`, `@Max`, `@NotNull`). El `OrderHandler` valida antes de pasar al use case. Errores de validación devuelven HTTP 400 con mensaje estructurado — sin exponer stack traces.

```java
// GlobalErrorHandler mapea ValidationException → 400
case "ValidationException" -> HttpStatus.BAD_REQUEST;
```

### Exposición mínima de información en errores
`GlobalErrorHandler` devuelve solo `status`, `error`, `message` y `path`. Nunca expone:
- Stack traces
- Nombres de tablas DynamoDB
- Versiones internas de dependencias
- Información del servidor (`Server` header eliminado)

### HTTPS obligatorio
En producción todo el tráfico pasa por Application Load Balancer con certificado ACM. El ALB redirige HTTP → HTTPS. La app en Fargate solo escucha en el puerto interno — no expuesta directamente a internet.

### SQS — mensajes maliciosos
El consumidor `SqsOrderConsumer` maneja mensajes inválidos sin explotar:
- Deserialización en `try/catch` — mensaje inválido no crashea el consumer.
- Mensaje no procesable → **no se elimina** → SQS lo reintenta 3 veces → mueve a **Dead Letter Queue**.
- DLQ con alarma CloudWatch — alerta al equipo cuando llegan mensajes problemáticos.

### Optimistic Locking — prevención de race conditions
Cada escritura en DynamoDB incluye `ConditionExpression: version = N`. Si dos requests concurrentes intentan reservar el mismo ticket, solo uno gana. El perdedor recibe 409 — no se produce overselling silencioso.

---

## 3. Consideraciones adicionales en producción

| Riesgo | Mitigación |
|---|---|
| Credenciales expuestas en logs | `@Slf4j` nunca loguea campos sensibles. Jackson excluye campos de contraseña. |
| DynamoDB scan ilimitado | GSIs en lugar de full table scans. `findExpiredReservations` se migra a GSI en producción. |
| SQS message flooding | Dead Letter Queue + alarma CloudWatch. Visibilidad timeout 30s. |
| Agotamiento de tickets por bots | Rate limiting en API Gateway + CAPTCHA en frontend. |
| Tokens JWT (futuro) | Spring Security WebFlux con validación de tokens en filtro reactivo. |
| CORS | Configuración explícita de origins permitidos — no `*` en producción. |
