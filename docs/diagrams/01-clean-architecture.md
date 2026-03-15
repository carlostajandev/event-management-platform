# Clean Architecture — Layer Diagram

## Dependency Rule

```
ALLOWED:    Infrastructure → Application → Domain
FORBIDDEN:  Domain → Application
FORBIDDEN:  Domain → Infrastructure  
FORBIDDEN:  Application → Infrastructure
```

## Package Structure and Dependencies

```
com.nequi.ticketing/
│
├── domain/                          ★ NO imports from Spring, AWS, or any framework
│   ├── model/
│   │   ├── Event.java               record/class — pure Java
│   │   ├── Ticket.java
│   │   ├── Order.java
│   │   ├── TicketStatus.java        enum
│   │   └── OrderStatus.java         enum
│   │
│   ├── valueobject/
│   │   ├── EventId.java             record — wraps String, validates format
│   │   ├── TicketId.java
│   │   ├── OrderId.java
│   │   ├── Money.java               record — amount + currency
│   │   └── Venue.java               record — name + city + country
│   │
│   ├── repository/                  interfaces only — output ports
│   │   ├── EventRepository.java     Mono<Event> findById(EventId)
│   │   ├── TicketRepository.java    Flux<Ticket> findAvailable(EventId, int)
│   │   ├── OrderRepository.java     Mono<Order> save(Order)
│   │   ├── IdempotencyRepository.java
│   │   └── AuditRepository.java
│   │
│   ├── service/
│   │   └── TicketStateMachine.java  enforces valid transitions, throws on invalid
│   │
│   └── exception/
│       ├── EventNotFoundException.java
│       ├── TicketNotAvailableException.java
│       ├── InvalidTicketStateException.java
│       └── OrderNotFoundException.java
│
├── application/                     depends on domain ONLY
│   ├── usecase/                     one class per use case
│   │   ├── CreateEventService.java
│   │   ├── GetEventService.java
│   │   ├── ReserveTicketsService.java     ← core business logic
│   │   ├── CreatePurchaseOrderService.java
│   │   ├── ProcessOrderService.java       ← SQS consumer logic
│   │   ├── ReleaseExpiredReservationsService.java
│   │   ├── QueryOrderStatusService.java
│   │   └── AuditService.java
│   │
│   ├── port/
│   │   ├── in/                      driving ports (what clients can do)
│   │   │   ├── CreateEventUseCase.java
│   │   │   ├── GetEventUseCase.java
│   │   │   ├── ReserveTicketsUseCase.java
│   │   │   ├── CreatePurchaseOrderUseCase.java
│   │   │   ├── QueryOrderStatusUseCase.java
│   │   │   └── GetAvailabilityUseCase.java
│   │   │
│   │   └── out/                     driven ports (what the app needs)
│   │       ├── EventRepository.java → implemented by EventDynamoDbRepository
│   │       ├── TicketRepository.java
│   │       ├── OrderRepository.java
│   │       ├── IdempotencyRepository.java
│   │       ├── MessagePublisher.java → implemented by SqsMessagePublisher
│   │       └── AuditRepository.java
│   │
│   └── dto/
│       ├── CreateEventRequest.java
│       ├── EventResponse.java
│       ├── ReserveTicketsRequest.java
│       ├── OrderResponse.java
│       ├── AvailabilityResponse.java
│       └── PagedResponse.java
│
└── infrastructure/                  depends on application ports
    ├── config/
    │   ├── DynamoDbConfig.java       creates DynamoDbAsyncClient bean
    │   ├── SqsConfig.java            creates SqsAsyncClient bean
    │   ├── ShedLockConfig.java       creates DynamoDBLockProvider bean
    │   ├── CorsConfig.java           WebFlux CORS configuration
    │   ├── DynamoDbTableInitializer.java  creates tables on startup
    │   └── TicketingProperties.java  @ConfigurationProperties
    │
    ├── persistence/dynamodb/
    │   ├── entity/                   DynamoDB item shape
    │   ├── mapper/                   Entity ↔ Domain model
    │   └── repository/               implements domain repository interfaces
    │       ├── EventDynamoDbRepository.java     @CircuitBreaker(name="dynamodb")
    │       ├── TicketDynamoDbRepository.java    @CircuitBreaker(name="dynamodb")
    │       ├── OrderDynamoDbRepository.java     @CircuitBreaker(name="dynamodb")
    │       ├── IdempotencyDynamoDbRepository.java
    │       └── AuditDynamoDbRepository.java
    │
    ├── messaging/sqs/
    │   ├── SqsMessagePublisher.java  implements MessagePublisher
    │   │                             @CircuitBreaker(name="sqs") @Retry(name="sqs-publish")
    │   └── SqsOrderConsumer.java     polls SQS, calls ProcessOrderUseCase
    │
    ├── scheduler/
    │   └── ExpiredReservationScheduler.java  @SchedulerLock, calls ReleaseExpiredUseCase
    │
    ├── web/
    │   ├── filter/
    │   │   └── CorrelationIdFilter.java  sets X-Correlation-Id in MDC
    │   ├── handler/
    │   │   ├── EventHandler.java
    │   │   ├── OrderHandler.java
    │   │   └── AvailabilityHandler.java
    │   └── router/
    │       ├── EventRouter.java      RouterFunction<ServerResponse>
    │       └── OrderRouter.java
    │
    └── shared/
        └── error/
            └── GlobalErrorHandler.java  maps exceptions to HTTP responses
```
