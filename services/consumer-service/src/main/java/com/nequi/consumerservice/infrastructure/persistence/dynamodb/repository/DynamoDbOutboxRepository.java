package com.nequi.consumerservice.infrastructure.persistence.dynamodb.repository;

import com.nequi.consumerservice.infrastructure.persistence.dynamodb.entity.OutboxEntity;
import com.nequi.shared.domain.model.OutboxMessage;
import com.nequi.shared.domain.port.OutboxRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * DynamoDB implementation of {@link OutboxRepository} for consumer-service.
 *
 * <p>Consumer-service uses this repository to:
 * <ol>
 *   <li>Find unpublished messages (GSI1 query)</li>
 *   <li>Mark messages as published after successful SQS delivery</li>
 * </ol>
 *
 * <p>All public methods protected by {@code @CircuitBreaker(name = "dynamodb")}.
 */
@Slf4j
@Repository
@CircuitBreaker(name = "dynamodb")
public class DynamoDbOutboxRepository implements OutboxRepository {

    private static final String OUTBOX_PK_PREFIX     = "OUTBOX#";
    private static final String PUBLISHED_FALSE       = "PUBLISHED#false";
    private static final String PUBLISHED_TRUE        = "PUBLISHED#true";
    private static final String GSI1_INDEX_NAME       = "GSI1";

    private final DynamoDbEnhancedAsyncClient       enhancedClient;
    private final DynamoDbAsyncClient               rawClient;
    private final DynamoDbAsyncTable<OutboxEntity>  table;
    private final String                            tableName;

    public DynamoDbOutboxRepository(
            DynamoDbEnhancedAsyncClient enhancedClient,
            DynamoDbAsyncClient dynamoDbAsyncClient,
            @Value("${aws.dynamodb.table.outbox:emp-outbox}") String tableName) {
        this.enhancedClient = enhancedClient;
        this.rawClient      = dynamoDbAsyncClient;
        this.tableName      = tableName;
        this.table          = enhancedClient.table(tableName, TableSchema.fromBean(OutboxEntity.class));
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    @Override
    public Mono<OutboxMessage> save(OutboxMessage message) {
        OutboxEntity entity = toEntity(message);
        return Mono.fromFuture(table.putItem(entity))
                .thenReturn(message)
                .doOnError(ex -> log.error("Failed to save outbox message: id={}, error={}", message.id(), ex.getMessage(), ex));
    }

    // ── FindUnpublished — GSI1 query ──────────────────────────────────────────

    @Override
    public Flux<OutboxMessage> findUnpublished() {
        QueryConditional queryConditional = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(PUBLISHED_FALSE).build()
        );

        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(queryConditional)
                .build();

        return Flux.from(table.index(GSI1_INDEX_NAME).query(request))
                .flatMap(page -> Flux.fromIterable(page.items()))
                .map(this::toDomain)
                .doOnSubscribe(sub -> log.debug("Querying unpublished outbox messages via GSI1"))
                .doOnError(ex -> log.error("Error querying unpublished outbox messages: {}", ex.getMessage(), ex));
    }

    // ── MarkPublished ─────────────────────────────────────────────────────────

    @Override
    public Mono<Void> markPublished(String messageId) {
        QueryConditional queryConditional = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(OUTBOX_PK_PREFIX + messageId).build()
        );

        return Flux.from(table.query(QueryEnhancedRequest.builder()
                        .queryConditional(queryConditional).limit(1).build()))
                .flatMap(page -> Flux.fromIterable(page.items()))
                .next()
                .flatMap(entity -> {
                    Map<String, AttributeValue> key = new HashMap<>();
                    key.put("PK", AttributeValue.fromS(OUTBOX_PK_PREFIX + messageId));
                    key.put("SK", AttributeValue.fromS(entity.getSk()));

                    Map<String, AttributeValue> expressionValues = new HashMap<>();
                    expressionValues.put(":gsi1Pk",   AttributeValue.fromS(PUBLISHED_TRUE));
                    expressionValues.put(":published", AttributeValue.fromBool(true));

                    UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                            .tableName(tableName)
                            .key(key)
                            .updateExpression("SET GSI1PK = :gsi1Pk, published = :published")
                            .expressionAttributeValues(expressionValues)
                            .build();

                    return Mono.fromFuture(rawClient.updateItem(updateRequest)).then();
                })
                .doOnSuccess(v -> log.debug("Marked outbox message as published: id={}", messageId))
                .doOnError(ex -> log.error("Error marking outbox message published: id={}, error={}", messageId, ex.getMessage(), ex));
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Override
    public Mono<Void> delete(String messageId) {
        QueryConditional queryConditional = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(OUTBOX_PK_PREFIX + messageId).build()
        );

        return Flux.from(table.query(QueryEnhancedRequest.builder()
                        .queryConditional(queryConditional).limit(1).build()))
                .flatMap(page -> Flux.fromIterable(page.items()))
                .next()
                .flatMap(entity -> {
                    Key k = Key.builder()
                            .partitionValue(OUTBOX_PK_PREFIX + messageId)
                            .sortValue(entity.getSk())
                            .build();
                    return Mono.fromFuture(table.deleteItem(k)).then();
                })
                .doOnError(ex -> log.error("Error deleting outbox message: id={}, error={}", messageId, ex.getMessage(), ex));
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private OutboxEntity toEntity(OutboxMessage message) {
        OutboxEntity entity = new OutboxEntity();
        entity.setPk(OUTBOX_PK_PREFIX + message.id());
        entity.setSk("CREATED_AT#" + message.createdAt().toString());
        entity.setGsi1Pk(message.published() ? PUBLISHED_TRUE : PUBLISHED_FALSE);
        entity.setId(message.id());
        entity.setAggregateId(message.aggregateId());
        entity.setAggregateType(message.aggregateType());
        entity.setEventType(message.eventType());
        entity.setPayload(message.payload());
        entity.setPublished(message.published());
        entity.setCreatedAt(message.createdAt().toString());
        entity.setTtl(message.ttl());
        return entity;
    }

    private OutboxMessage toDomain(OutboxEntity entity) {
        return new OutboxMessage(
                entity.getId(),
                entity.getAggregateId(),
                entity.getAggregateType(),
                entity.getEventType(),
                entity.getPayload(),
                entity.isPublished(),
                Instant.parse(entity.getCreatedAt()),
                entity.getTtl()
        );
    }
}
