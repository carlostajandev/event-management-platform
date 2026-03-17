package com.nequi.orderservice.infrastructure.persistence.dynamodb.repository;

import com.nequi.orderservice.infrastructure.persistence.dynamodb.entity.ReservationEntity;
import com.nequi.shared.domain.model.Reservation;
import com.nequi.shared.domain.model.ReservationStatus;
import com.nequi.shared.domain.port.ReservationRepository;
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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Read-side DynamoDB adapter for the {@code emp-reservations} table, used by order-service.
 *
 * <p>order-service needs to look up reservations (findById) to validate them before
 * creating an order. The reservation-service owns the table; this is a read-only
 * adapter that provides just the findById operation needed by CreateOrderService.
 */
@Slf4j
@Repository
@CircuitBreaker(name = "dynamodb")
public class DynamoDbReservationRepository implements ReservationRepository {

    private static final String RESERVATION_PK_PREFIX = "RESERVATION#";

    private final DynamoDbAsyncTable<ReservationEntity> table;

    public DynamoDbReservationRepository(
            DynamoDbEnhancedAsyncClient enhancedClient,
            @Value("${aws.dynamodb.table.reservations:emp-reservations}") String tableName) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(ReservationEntity.class));
    }

    @Override
    public Mono<Reservation> findById(String reservationId) {
        QueryConditional queryConditional = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(RESERVATION_PK_PREFIX + reservationId).build()
        );

        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(queryConditional)
                .limit(1)
                .build();

        return Flux.from(table.query(request))
                .flatMap(page -> Flux.fromIterable(page.items()))
                .next()
                .map(this::toDomain)
                .doOnError(ex -> log.error("Error finding reservation: id={}, error={}", reservationId, ex.getMessage(), ex));
    }

    @Override
    public Mono<Reservation> save(Reservation reservation) {
        throw new UnsupportedOperationException("order-service does not own the reservations table");
    }

    @Override
    public Flux<Reservation> findByEventId(String eventId) {
        throw new UnsupportedOperationException("order-service does not need findByEventId");
    }

    @Override
    public Flux<Reservation> findByUserId(String userId) {
        throw new UnsupportedOperationException("order-service does not need findByUserId");
    }

    @Override
    public Flux<Reservation> findExpiredReservations(Instant before) {
        throw new UnsupportedOperationException("order-service does not need findExpiredReservations");
    }

    @Override
    public Mono<Reservation> updateStatus(String reservationId, ReservationStatus newStatus) {
        throw new UnsupportedOperationException("order-service does not own the reservations table");
    }

    @Override
    public Mono<Void> delete(String reservationId) {
        throw new UnsupportedOperationException("order-service does not own the reservations table");
    }

    // ── Mapping: Entity → Domain ──────────────────────────────────────────────

    private Reservation toDomain(ReservationEntity entity) {
        return new Reservation(
                entity.getId(),
                entity.getEventId(),
                entity.getUserId(),
                entity.getSeatsCount(),
                new BigDecimal(entity.getTotalAmount()),
                entity.getCurrency(),
                ReservationStatus.valueOf(entity.getStatus()),
                Instant.parse(entity.getExpiresAt()),
                entity.getTtl(),
                Instant.parse(entity.getCreatedAt()),
                Instant.parse(entity.getUpdatedAt())
        );
    }
}
