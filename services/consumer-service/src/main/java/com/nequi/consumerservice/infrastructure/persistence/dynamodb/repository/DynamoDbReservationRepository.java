package com.nequi.consumerservice.infrastructure.persistence.dynamodb.repository;

import com.nequi.consumerservice.infrastructure.persistence.dynamodb.entity.ReservationEntity;
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
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * DynamoDB implementation of {@link ReservationRepository} for consumer-service.
 *
 * <p>Consumer-service only needs to update reservation status to CONFIRMED after
 * successfully processing an ORDER_PLACED event. Read and save operations use the
 * minimal set required to support the {@link com.nequi.consumerservice.application.usecase.ProcessOrderService}.
 *
 * <p>All public methods protected by {@code @CircuitBreaker(name = "dynamodb")}.
 */
@Slf4j
@Repository
@CircuitBreaker(name = "dynamodb")
public class DynamoDbReservationRepository implements ReservationRepository {

    private static final String RESERVATION_PK_PREFIX = "RESERVATION#";
    private static final String USER_SK_PREFIX        = "USER#";
    private static final String STATUS_GSI_PREFIX     = "STATUS#";

    private final DynamoDbEnhancedAsyncClient             enhancedClient;
    private final DynamoDbAsyncClient                     rawClient;
    private final DynamoDbAsyncTable<ReservationEntity>   table;
    private final String                                  tableName;

    public DynamoDbReservationRepository(
            DynamoDbEnhancedAsyncClient enhancedClient,
            DynamoDbAsyncClient dynamoDbAsyncClient,
            @Value("${aws.dynamodb.table.reservations:emp-reservations}") String tableName) {
        this.enhancedClient = enhancedClient;
        this.rawClient      = dynamoDbAsyncClient;
        this.tableName      = tableName;
        this.table          = enhancedClient.table(tableName, TableSchema.fromBean(ReservationEntity.class));
    }

    // ── Save — not used in consumer-service ──────────────────────────────────

    @Override
    public Mono<Reservation> save(Reservation reservation) {
        ReservationEntity entity = toEntity(reservation);
        return Mono.fromFuture(table.putItem(entity))
                .thenReturn(reservation);
    }

    // ── FindById ──────────────────────────────────────────────────────────────

    @Override
    public Mono<Reservation> findById(String reservationId) {
        QueryConditional queryConditional = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(RESERVATION_PK_PREFIX + reservationId).build()
        );

        return Flux.from(table.query(QueryEnhancedRequest.builder()
                        .queryConditional(queryConditional).limit(1).build()))
                .flatMap(page -> Flux.fromIterable(page.items()))
                .next()
                .map(this::toDomain)
                .doOnError(ex -> log.error("Error finding reservation: id={}, error={}", reservationId, ex.getMessage(), ex));
    }

    // ── FindByEventId ─────────────────────────────────────────────────────────

    @Override
    public Flux<Reservation> findByEventId(String eventId) {
        return Flux.from(table.scan())
                .flatMap(page -> Flux.fromIterable(page.items()))
                .filter(entity -> eventId.equals(entity.getEventId()))
                .map(this::toDomain);
    }

    // ── FindByUserId ──────────────────────────────────────────────────────────

    @Override
    public Flux<Reservation> findByUserId(String userId) {
        return Flux.from(table.scan())
                .flatMap(page -> Flux.fromIterable(page.items()))
                .filter(entity -> userId.equals(entity.getUserId()))
                .map(this::toDomain);
    }

    // ── FindExpiredReservations — not needed in consumer-service ──────────────

    @Override
    public Flux<Reservation> findExpiredReservations(Instant before) {
        return Flux.empty();
    }

    // ── UpdateStatus — primary operation for consumer-service ─────────────────

    @Override
    public Mono<Reservation> updateStatus(String reservationId, ReservationStatus newStatus) {
        return findById(reservationId)
                .flatMap(reservation -> {
                    Map<String, AttributeValue> key = new HashMap<>();
                    key.put("PK", AttributeValue.fromS(RESERVATION_PK_PREFIX + reservationId));
                    key.put("SK", AttributeValue.fromS(USER_SK_PREFIX + reservation.userId()));

                    Map<String, AttributeValue> expressionValues = new HashMap<>();
                    expressionValues.put(":newStatus",  AttributeValue.fromS(newStatus.name()));
                    expressionValues.put(":gsi1Pk",     AttributeValue.fromS(STATUS_GSI_PREFIX + newStatus.name()));
                    expressionValues.put(":updatedAt",  AttributeValue.fromS(Instant.now().toString()));

                    Map<String, String> expressionNames = new HashMap<>();
                    expressionNames.put("#s", "status");

                    UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                            .tableName(tableName)
                            .key(key)
                            .updateExpression("SET #s = :newStatus, GSI1PK = :gsi1Pk, updatedAt = :updatedAt")
                            .expressionAttributeValues(expressionValues)
                            .expressionAttributeNames(expressionNames)
                            .returnValues(ReturnValue.ALL_NEW)
                            .build();

                    return Mono.fromFuture(rawClient.updateItem(updateRequest))
                            .thenReturn(reservation.confirm())
                            .doOnSuccess(r -> log.info("Reservation status updated: id={}, newStatus={}", reservationId, newStatus))
                            .doOnError(ex -> log.error("Error updating reservation status: id={}, error={}", reservationId, ex.getMessage(), ex));
                });
    }

    // ── Delete — not used in consumer-service ─────────────────────────────────

    @Override
    public Mono<Void> delete(String reservationId) {
        return Mono.error(new UnsupportedOperationException("delete not used in consumer-service"));
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private ReservationEntity toEntity(Reservation reservation) {
        ReservationEntity entity = new ReservationEntity();
        entity.setPk(RESERVATION_PK_PREFIX + reservation.id());
        entity.setSk(USER_SK_PREFIX + reservation.userId());
        entity.setGsi1Pk(STATUS_GSI_PREFIX + reservation.status().name());
        entity.setGsi1Sk(reservation.expiresAt().toString());
        entity.setId(reservation.id());
        entity.setEventId(reservation.eventId());
        entity.setUserId(reservation.userId());
        entity.setSeatsCount(reservation.seatsCount());
        entity.setTotalAmount(reservation.totalAmount().toPlainString());
        entity.setCurrency(reservation.currency());
        entity.setStatus(reservation.status().name());
        entity.setExpiresAt(reservation.expiresAt().toString());
        entity.setTtl(reservation.ttl());
        entity.setCreatedAt(reservation.createdAt().toString());
        entity.setUpdatedAt(reservation.updatedAt().toString());
        return entity;
    }

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
