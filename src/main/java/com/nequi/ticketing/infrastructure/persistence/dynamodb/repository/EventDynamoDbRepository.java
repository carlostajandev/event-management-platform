package com.nequi.ticketing.infrastructure.persistence.dynamodb.repository;

import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.repository.EventRepository;
import com.nequi.ticketing.domain.valueobject.EventId;
import com.nequi.ticketing.infrastructure.config.AwsProperties;
import com.nequi.ticketing.infrastructure.persistence.dynamodb.entity.EventDynamoDbEntity;
import com.nequi.ticketing.infrastructure.persistence.dynamodb.mapper.EventDynamoDbMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.util.Map;

/**
 * DynamoDB implementation of {@link EventRepository}.
 *
 * <p>Optimistic locking is implemented manually via ConditionExpression:
 * on update, we check that the stored version matches the expected version.
 * If another request updated the record first, DynamoDB throws
 * {@link ConditionalCheckFailedException} and the caller must retry.
 */
@Repository
public class EventDynamoDbRepository implements EventRepository {

    private static final Logger log = LoggerFactory.getLogger(EventDynamoDbRepository.class);

    private final DynamoDbAsyncTable<EventDynamoDbEntity> table;
    private final EventDynamoDbMapper mapper;

    public EventDynamoDbRepository(
            DynamoDbEnhancedAsyncClient enhancedClient,
            AwsProperties awsProperties,
            EventDynamoDbMapper mapper) {

        this.table = enhancedClient.table(
                awsProperties.dynamodb().tables().events(),
                TableSchema.fromBean(EventDynamoDbEntity.class));
        this.mapper = mapper;
    }

    @CircuitBreaker(name = "dynamodb")
    @Override
    public Mono<Event> save(Event event) {
        EventDynamoDbEntity entity = mapper.toEntity(event);

        // Condition: item must NOT exist yet (attribute_not_exists on PK)
        Expression condition = Expression.builder()
                .expression("attribute_not_exists(eventId)")
                .build();

        PutItemEnhancedRequest<EventDynamoDbEntity> request = PutItemEnhancedRequest
                .<EventDynamoDbEntity>builder(EventDynamoDbEntity.class)
                .item(entity)
                .conditionExpression(condition)
                .build();

        return Mono.fromCompletionStage(() -> table.putItem(request))
                .thenReturn(event)
                .doOnSuccess(e -> log.debug("Event saved: eventId={}", e.eventId().value()));
    }

    @CircuitBreaker(name = "dynamodb")
    @Override
    public Mono<Event> findById(EventId eventId) {
        Key key = Key.builder().partitionValue(eventId.value()).build();

        return Mono.fromCompletionStage(() -> table.getItem(key))
                .map(mapper::toDomain);
    }

    @CircuitBreaker(name = "dynamodb")
    @Override
    public Flux<Event> findAll() {
        return Flux.from(table.scan(ScanEnhancedRequest.builder().build()).items())
                .map(mapper::toDomain);
    }

    @CircuitBreaker(name = "dynamodb")
    @Override
    public Mono<Event> update(Event event) {
        EventDynamoDbEntity entity = mapper.toEntity(event);

        // Optimistic locking: only update if stored version = expected version
        // This prevents two concurrent requests from overwriting each other
        Expression condition = Expression.builder()
                .expression("#version = :expectedVersion")
                .expressionNames(Map.of("#version", "version"))
                .expressionValues(Map.of(
                        ":expectedVersion",
                        AttributeValue.builder()
                                .n(String.valueOf(event.version() - 1))
                                .build()
                ))
                .build();

        PutItemEnhancedRequest<EventDynamoDbEntity> request = PutItemEnhancedRequest
                .<EventDynamoDbEntity>builder(EventDynamoDbEntity.class)
                .item(entity)
                .conditionExpression(condition)
                .build();

        return Mono.fromCompletionStage(() -> table.putItem(request))
                .thenReturn(event)
                .doOnSuccess(e -> log.debug("Event updated: eventId={}, version={}",
                        e.eventId().value(), e.version()))
                .onErrorMap(ConditionalCheckFailedException.class, ex ->
                        new RuntimeException("Concurrent modification detected for event: "
                                + event.eventId().value()));
    }

    @Override
    public Mono<Boolean> existsById(EventId eventId) {
        return findById(eventId)
                .map(e -> true)
                .defaultIfEmpty(false);
    }
}