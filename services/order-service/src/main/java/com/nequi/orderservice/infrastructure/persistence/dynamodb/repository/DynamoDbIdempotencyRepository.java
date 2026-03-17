package com.nequi.orderservice.infrastructure.persistence.dynamodb.repository;

import com.nequi.orderservice.infrastructure.persistence.dynamodb.entity.IdempotencyEntity;
import com.nequi.shared.domain.model.IdempotencyRecord;
import com.nequi.shared.domain.port.IdempotencyRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.time.Instant;

/**
 * DynamoDB implementation of {@link IdempotencyRepository}.
 *
 * <p>Table: {@code emp-idempotency-keys}
 * <ul>
 *   <li>PK: {@code KEY#<idempotency-uuid>}</li>
 *   <li>SK: {@code IDEMPOTENCY} (fixed value — point-lookup by PK)</li>
 *   <li>TTL: 24h (epoch seconds) — auto-deleted by DynamoDB</li>
 * </ul>
 *
 * <p>Protected by {@code @CircuitBreaker(name = "dynamodb")}.
 */
@Slf4j
@Repository
@CircuitBreaker(name = "dynamodb")
public class DynamoDbIdempotencyRepository implements IdempotencyRepository {

    private static final String KEY_PK_PREFIX = "KEY#";
    private static final String FIXED_SK      = "IDEMPOTENCY";

    private final DynamoDbAsyncTable<IdempotencyEntity> table;

    public DynamoDbIdempotencyRepository(
            DynamoDbEnhancedAsyncClient enhancedClient,
            @Value("${aws.dynamodb.table.idempotency:emp-idempotency-keys}") String tableName) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(IdempotencyEntity.class));
    }

    // ── FindByKey ─────────────────────────────────────────────────────────────

    @Override
    public Mono<IdempotencyRecord> findByKey(String key) {
        Key dynamoKey = Key.builder()
                .partitionValue(KEY_PK_PREFIX + key)
                .sortValue(FIXED_SK)
                .build();

        return Mono.fromFuture(table.getItem(dynamoKey))
                .filter(entity -> entity != null)
                .map(this::toDomain)
                .doOnSuccess(r -> { if (r != null) log.debug("Found idempotency record: key={}", key); })
                .doOnError(ex -> log.error("Error finding idempotency record: key={}, error={}", key, ex.getMessage(), ex));
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    @Override
    public Mono<IdempotencyRecord> save(IdempotencyRecord record) {
        IdempotencyEntity entity = toEntity(record);
        return Mono.fromFuture(table.putItem(entity))
                .thenReturn(record)
                .doOnSuccess(r -> log.debug("Saved idempotency record: key={}, orderId={}", r.key(), r.orderId()))
                .doOnError(ex -> log.error("Failed to save idempotency record: key={}, error={}", record.key(), ex.getMessage(), ex));
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private IdempotencyEntity toEntity(IdempotencyRecord record) {
        IdempotencyEntity entity = new IdempotencyEntity();
        entity.setPk(KEY_PK_PREFIX + record.key());
        entity.setSk(FIXED_SK);
        entity.setKey(record.key());
        entity.setOrderId(record.orderId());
        entity.setCachedResponseJson(record.cachedResponseJson());
        entity.setCreatedAt(record.createdAt().toString());
        entity.setTtl(record.ttl());
        return entity;
    }

    private IdempotencyRecord toDomain(IdempotencyEntity entity) {
        return new IdempotencyRecord(
                entity.getKey(),
                entity.getOrderId(),
                entity.getCachedResponseJson(),
                Instant.parse(entity.getCreatedAt()),
                entity.getTtl()
        );
    }
}
