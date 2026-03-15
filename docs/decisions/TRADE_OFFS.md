# Trade-offs, Limitaciones y Mejoras

## Decisiones que cambiaría en producción

### 1. `findExpiredReservations` — Full table scan
**Situación actual**: El scheduler de expiración hace un `scan` completo de la tabla `emp-tickets` buscando los que tienen `status=RESERVED` y `expiresAt < now`.

**Problema en producción**: Con millones de tickets, un full scan es costoso ($) y lento.

**Solución real**: GSI sobre `(status, expiresAt)` o usar DynamoDB Streams + Lambda que reaccione a cambios. Alternativa más elegante: TTL nativo de DynamoDB con un Lambda trigger que libere el ticket cuando expire.

```
Producción:
tickets con expiresAt → DynamoDB TTL → Stream → Lambda → release ticket
```

---

### 2. SQS Standard vs FIFO
**Situación actual**: SQS Standard (at-least-once, sin orden garantizado).

**Problema**: Un mismo orderId podría procesarse dos veces en paralelo si hay reintentos rápidos.

**Mitigación actual**: `ProcessOrderService` es idempotente — detecta estado final y hace skip.

**En producción**: SQS FIFO con `MessageGroupId = orderId` garantiza at-most-once processing por orden. Más costoso pero más predecible.

---

### 3. Reserva de tickets sin lock distribuido
**Situación actual**: Optimistic locking con conditional writes. Si 100 usuarios compiten por el último ticket, 99 recibirán 409 y tendrán que reintentar.

**Problema**: Alta contención en eventos populares genera muchos 409 y reintentos que saturan la API.

**Solución real para alta demanda**:
- **Pre-asignación de rangos**: cada instancia de la app recibe un rango de ticketIds para asignar — sin contención.
- **Redis + Lua scripts**: reserva atómica con TTL en memoria, flush a DynamoDB async.
- **Virtual waiting room**: cola virtual para picos tipo venta de entradas Coldplay.

---

### 4. Polling de SQS vs Event-driven
**Situación actual**: `@Scheduled` poll cada 5 segundos.

**Problema**: Latencia de hasta 5s + costos de polling cuando la cola está vacía.

**En producción**: SQS Long Polling (waitTimeSeconds=20) ya está configurado, pero en ECS lo ideal es usar **Spring Cloud AWS SQS Listener** o directamente **AWS Lambda** como consumidor — reacciona en milisegundos y escala a cero cuando no hay mensajes.

---

### 5. Single-region vs Multi-region
**Situación actual**: Diseñado para us-east-1.

**Para un sistema de ticketing nacional colombiano**: DynamoDB Global Tables con replicación a sa-east-1 (São Paulo, la región más cercana) + Route 53 latency-based routing.

---

## Limitaciones conocidas de esta implementación

| Limitación | Impacto | Solución |
|---|---|---|
| No hay autenticación JWT | Cualquiera puede crear órdenes | Spring Security WebFlux + Cognito |
| CORS no configurado | No apto para browser directo | WebFluxConfigurer + CORS policy |
| Logs sin correlation ID global | Difícil trazar una transacción | MDC con `traceId` propagado via WebFilter |
| Sin circuit breaker activo en DynamoDB | Fallo de DynamoDB cascada a toda la app | Resilience4j CircuitBreaker en repos |
| Scheduler no distribuido | Con 3 instancias, 3 schedulers corren en paralelo | ShedLock o EventBridge Scheduler |
| Sin paginación en `GET /events` | Con 10k eventos, la respuesta es enorme | Cursor-based pagination con `lastEvaluatedKey` |
| Audit table sin uso real | Se crea pero no se escribe | Implementar AuditService que persista cambios |

---

## Mejoras para un entorno productivo real

### Observabilidad
```yaml
# Trazas distribuidas con AWS X-Ray
management:
  tracing:
    sampling:
      probability: 1.0
```
- **CloudWatch Structured Logs** — JSON con `traceId`, `orderId`, `userId`, `duration`
- **CloudWatch Dashboard** — métricas de negocio: tickets/min vendidos, órdenes fallidas, latencia P95/P99
- **Alarmas**: tasa de error >1%, latencia P99 >500ms, DLQ con mensajes

### Performance
- **DynamoDB DAX** (cache in-memory) para `GET /events/{eventId}` — reducir latencia de 10ms a <1ms
- **ElastiCache Redis** para sesiones y rate limiting distribuido
- **CDN CloudFront** frente al ALB para respuestas cacheables (disponibilidad de eventos)

### Resiliencia
```java
// Circuit breaker en DynamoDB repository
@CircuitBreaker(name = "dynamodb", fallbackMethod = "fallbackFindById")
public Mono<Event> findById(EventId eventId) { ... }
```

### Cost optimization
- **DynamoDB on-demand** en producción (PAY_PER_REQUEST) — sin provisioning waste
- **ECS Fargate Spot** para el consumer SQS — hasta 70% de ahorro, tolerante a interrupciones
- **Reserved Capacity DynamoDB** para carga predecible base — ahorro del 20-40%
- **SQS Long Polling** — reduce llamadas vacías de 8640/día a ~432/día por instancia

---

## Reflexión: ¿Qué haría diferente desde el día 1?

1. **Event Sourcing** para el dominio de tickets — en lugar de estado mutable, cada cambio es un evento inmutable. Audit trail gratuito, replay de estado, fácil debugging.

2. **CQRS explícito** — separar el modelo de escritura (commands) del modelo de lectura (queries). El `GET /availability` podría servirse desde una proyección desnormalizada en ElastiCache, sin tocar DynamoDB.

3. **Contract testing con Pact** — los consumers (frontend, mobile) publican contratos; el API los valida en CI. Evita breaking changes silenciosos.

4. **Canary deployments** con CodeDeploy — en un sistema de ticketing, un bug en producción durante una venta popular es catastrófico. Canary al 5% con rollback automático si el error rate sube.

5. **DynamoDB Streams + Lambda para eventos de dominio** — cuando un ticket se vende, un Lambda puede actualizar contadores en tiempo real, notificar al usuario por SNS, y actualizar el dashboard sin acoplar esa lógica al servicio principal.
