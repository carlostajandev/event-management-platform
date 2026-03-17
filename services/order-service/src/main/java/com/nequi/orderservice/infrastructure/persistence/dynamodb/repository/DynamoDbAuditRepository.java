package com.nequi.orderservice.infrastructure.persistence.dynamodb.repository;

import com.nequi.orderservice.infrastructure.persistence.dynamodb.entity.AuditEntity;
import com.nequi.shared.domain.model.AuditEntry;
import com.nequi.shared.domain.port.AuditRepository;
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

import java.time.Instant;

/**
 * DynamoDB implementation of {@link AuditRepository} for order-service.
 *
 * <p>Table: {@code emp-audit}
 * <ul>
 *   <li>PK: {@code AUDIT#<entityId>}</li>
 *   <li>SK: {@code TIMESTAMP#<iso-timestamp>} (sortable chronologically)</li>
 *   <li>TTL: 90 days (epoch seconds)</li>
 * </ul>
 *
 * <p>Protected by {@code @CircuitBreaker(name = "dynamodb")}.
 */
@Slf4j
@Repository
@CircuitBreaker(name = "dynamodb")
public class DynamoDbAuditRepository implements AuditRepository {

    private static final String AUDIT_PK_PREFIX     = "AUDIT#";
    private static final String TIMESTAMP_SK_PREFIX = "TIMESTAMP#";

    private final DynamoDbAsyncTable<AuditEntity> table;

    public DynamoDbAuditRepository(
            DynamoDbEnhancedAsyncClient enhancedClient,
            @Value("${aws.dynamodb.table.audit:emp-audit}") String tableName) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(AuditEntity.class));
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    @Override
    public Mono<AuditEntry> save(AuditEntry entry) {
        AuditEntity entity = toEntity(entry);
        return Mono.fromFuture(table.putItem(entity))
                .thenReturn(entry)
                .doOnSuccess(e -> log.debug("Saved audit entry: entityId={}, {}->{}", e.entityId(), e.fromStatus(), e.toStatus()))
                .doOnError(ex -> log.error("Failed to save audit entry: entityId={}, error={}", entry.entityId(), ex.getMessage(), ex));
    }

    // ── FindByEntityId ────────────────────────────────────────────────────────

    @Override
    public Flux<AuditEntry> findByEntityId(String entityId) {
        QueryConditional queryConditional = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(AUDIT_PK_PREFIX + entityId).build()
        );

        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(queryConditional)
                .build();

        return Flux.from(table.query(request))
                .flatMap(page -> Flux.fromIterable(page.items()))
                .map(this::toDomain)
                .doOnSubscribe(sub -> log.debug("Querying audit entries for entityId={}", entityId))
                .doOnError(ex -> log.error("Error querying audit entries: entityId={}, error={}", entityId, ex.getMessage(), ex));
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private AuditEntity toEntity(AuditEntry entry) {
        AuditEntity entity = new AuditEntity();
        entity.setPk(AUDIT_PK_PREFIX + entry.entityId());
        entity.setSk(TIMESTAMP_SK_PREFIX + entry.timestamp().toString());
        entity.setEntityId(entry.entityId());
        entity.setEntityType(entry.entityType());
        entity.setFromStatus(entry.fromStatus());
        entity.setToStatus(entry.toStatus());
        entity.setUserId(entry.userId());
        entity.setCorrelationId(entry.correlationId());
        entity.setTimestamp(entry.timestamp().toString());
        entity.setTtl(entry.ttl());
        return entity;
    }

    private AuditEntry toDomain(AuditEntity entity) {
        return new AuditEntry(
                entity.getEntityId(),
                entity.getEntityType(),
                entity.getFromStatus(),
                entity.getToStatus(),
                entity.getUserId(),
                entity.getCorrelationId(),
                Instant.parse(entity.getTimestamp()),
                entity.getTtl()
        );
    }
}
