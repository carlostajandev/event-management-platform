# Clean Architecture — Microservices Monorepo v2

## Dependency Rule

```
ALLOWED:    Infrastructure → Application → Domain
FORBIDDEN:  Domain → Application
FORBIDDEN:  Domain → Infrastructure
FORBIDDEN:  Application → Infrastructure
```

## Module Structure

```mermaid
graph TB
    subgraph "shared/domain (zero framework deps)"
        DM[domain/model<br/>Java 25 Records<br/>Event · Reservation · Order<br/>OutboxMessage · AuditEntry]
        DE[domain/event<br/>Sealed DomainEvent<br/>TicketReserved · OrderPlaced<br/>ReservationExpired · OrderConfirmed]
        DX[domain/exception<br/>EventNotFoundException<br/>TicketNotAvailableException<br/>ConcurrentModificationException]
        DP[domain/port<br/>EventRepository<br/>ReservationRepository<br/>OrderRepository · OutboxRepository<br/>IdempotencyRepository · AuditRepository]
    end

    subgraph "shared/infrastructure (AWS config)"
        IC[config<br/>DynamoDbConfig · SqsConfig<br/>JacksonConfig]
        IU[util<br/>CorrelationIdFilter<br/>GlobalErrorHandler · ErrorResponse]
    end

    subgraph "services/event-service (:8081)"
        ES_D[domain<br/>EventNotFoundException]
        ES_A[application<br/>CreateEventUseCase → CreateEventService<br/>GetEventUseCase → GetEventService<br/>ListEventsUseCase → ListEventsService<br/>GetAvailabilityUseCase → GetAvailabilityService]
        ES_I[infrastructure<br/>DynamoDbEventRepository<br/>EventHandler · EventRouter<br/>DynamoDbTableInitializer]
    end

    subgraph "services/reservation-service (:8082)"
        RS_A[application<br/>ReserveTicketsUseCase → ReserveTicketsService<br/>GetReservationUseCase → GetReservationService<br/>CancelReservationUseCase → CancelReservationService<br/>ReleaseExpiredUseCase → ReleaseExpiredService]
        RS_I[infrastructure<br/>DynamoDbReservationRepository<br/>DynamoDbAuditRepository<br/>ReservationExpiryScheduler<br/>ReservationHandler · ReservationRouter]
    end

    subgraph "services/order-service (:8083)"
        OS_A[application<br/>CreateOrderUseCase → CreateOrderService<br/>GetOrderStatusUseCase → GetOrderStatusService]
        OS_I[infrastructure<br/>DynamoDbOrderRepository<br/>TransactWriteItems outbox<br/>DynamoDbIdempotencyRepository<br/>OrderHandler · OrderRouter]
    end

    subgraph "services/consumer-service (:8084)"
        CS_A[application<br/>ProcessOrderService<br/>OutboxPollerService]
        CS_I[infrastructure<br/>OrderMessageConsumer @SqsListener<br/>OutboxPoller @Scheduled<br/>SqsQueueInitializer]
    end

    IC --> DM
    IU --> DX

    ES_A --> DP
    ES_I --> ES_A
    ES_I --> IC

    RS_A --> DP
    RS_I --> RS_A
    RS_I --> IC

    OS_A --> DP
    OS_I --> OS_A
    OS_I --> IC

    CS_A --> DP
    CS_I --> CS_A
    CS_I --> IC
```

## Package Structure per Service

```
{service}/src/main/java/com/nequi/{service}/
│
├── application/
│   ├── port/in/        UseCase interfaces (input ports)
│   ├── port/out/       Repository + publisher interfaces (output ports)
│   ├── usecase/        Use case implementations (orchestration only)
│   └── dto/            Request/Response Java 25 Records
│
├── domain/             (in shared/domain — consumed as dependency)
│   ├── model/          Aggregate roots and value objects (Records)
│   ├── event/          Domain events (sealed Records)
│   ├── exception/      Business exceptions
│   └── port/           Repository interfaces
│
└── infrastructure/
    ├── config/         DynamoDB table initializer, validation config
    ├── persistence/
    │   ├── dynamodb/
    │   │   ├── entity/     DynamoDB @DynamoDbBean entities
    │   │   └── repository/ Port implementations
    ├── web/
    │   ├── handler/    WebFlux functional handlers
    │   └── router/     RouterFunction beans
    ├── messaging/      SQS listener (consumer-service only)
    └── scheduler/      @Scheduled jobs (reconciliation, outbox poller)
```

## Java 25 Features by Layer

| Layer | Feature | Example |
|---|---|---|
| Domain | Records | `record Event(String id, int availableCount, long version, ...)` |
| Domain | Sealed interfaces | `sealed interface DomainEvent permits TicketReserved, OrderPlaced...` |
| Application | Pattern matching | `switch(ex) { case EventNotFoundException e -> 404; ... }` |
| Infrastructure | Virtual Threads | `spring.threads.virtual.enabled: true` |
| Infrastructure | Pattern matching | `GlobalErrorHandler` maps exceptions to HTTP status codes |
