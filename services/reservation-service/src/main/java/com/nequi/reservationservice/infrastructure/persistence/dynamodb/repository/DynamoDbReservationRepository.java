package com.nequi.reservationservice.infrastructure.persistence.dynamodb.repository;

import com.nequi.reservationservice.infrastructure.persistence.dynamodb.entity.ReservationEntity;
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
 * DynamoDB implementation of {@link ReservationRepository}.
 *
 * <p>Uses two clients:
 * <ul>
 *   <li>{@link DynamoDbEnhancedAsyncClient} — high-level ORM for save/get/query operations</li>
 *   <li>{@link DynamoDbAsyncClient} — low-level client for {@code updateStatus} where we need
 *       fine-grained control over UpdateExpression and attribute names</li>
 * </ul>
 *
 * <p>GSI design for {@code findExpiredReservations}:
 * GSI1PK = {@code STATUS#ACTIVE}, GSI1SK = {@code expiresAt} (ISO-8601 sortable).
 * A range query {@code GSI1SK <= now.toString()} returns only expired active reservations
 * — O(results), not O(table). ISO-8601 strings sort lexicographically correctly.
 *
 * <p>All public methods are protected by {@code @CircuitBreaker(name = "dynamodb")}
 * so Resilience4j opens the circuit on sustained DynamoDB failures.
 */
@Slf4j
@Repository
@CircuitBreaker(name = "dynamodb")
public class DynamoDbReservationRepository implements ReservationRepository {

    private static final String RESERVATION_PK_PREFIX = "RESERVATION#";
    private static final String USER_SK_PREFIX         = "USER#";
    private static final String STATUS_GSI_PREFIX      = "STATUS#";
    private static final String GSI1_INDEX_NAME        = "GSI1";

    private final DynamoDbEnhancedAsyncClient         enhancedClient;
    private final DynamoDbAsyncClient                 rawClient;
    private final DynamoDbAsyncTable<ReservationEntity> table;
    private final String                               tableName;

    public DynamoDbReservationRepository(
            DynamoDbEnhancedAsyncClient enhancedClient,
            DynamoDbAsyncClient dynamoDbAsyncClient,
            @Value("${aws.dynamodb.table.reservations:emp-reservations}") String tableName) {
        this.enhancedClient = enhancedClient;
        this.rawClient      = dynamoDbAsyncClient;
        this.tableName      = tableName;
        this.table          = enhancedClient.table(tableName, TableSchema.fromBean(ReservationEntity.class));
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    @Override
    public Mono<Reservation> save(Reservation reservation) {
        ReservationEntity entity = toEntity(reservation);
        return Mono.fromFuture(table.putItem(entity))
                .thenReturn(reservation)
                .doOnSuccess(r -> log.debug("Saved reservation: id={}, status={}", r.id(), r.status()))
                .doOnError(ex -> log.error("Failed to save reservation: id={}, error={}", reservation.id(), ex.getMessage(), ex));
    }

    // ── FindById ──────────────────────────────────────────────────────────────

    @Override
    public Mono<Reservation> findById(String reservationId) {
        // We need to query by PK alone since we don't always know the userId (SK)
        // Use query on main table with PK = "RESERVATION#id"
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
                .doOnSuccess(r -> {
                    if (r != null) log.debug("Found reservation: id={}", reservationId);
                })
                .doOnError(ex -> log.error("Error finding reservation: id={}, error={}", reservationId, ex.getMessage(), ex));
    }

    // ── FindByEventId ─────────────────────────────────────────────────────────

    @Override
    public Flux<Reservation> findByEventId(String eventId) {
        // Filter using scan with FilterExpression — acceptable since this is a management query
        // For high-traffic paths, add a GSI on eventId
        QueryConditional queryConditional = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(RESERVATION_PK_PREFIX + eventId).build()
        );

        // Alternative: use scan with filter — implemented as simple scan for now
        return Flux.from(table.scan())
                .flatMap(page -> Flux.fromIterable(page.items()))
                .filter(entity -> eventId.equals(entity.getEventId()))
                .map(this::toDomain)
                .doOnSubscribe(sub -> log.debug("Querying reservations by eventId={}", eventId))
                .doOnError(ex -> log.error("Error querying reservations by eventId={}: {}", eventId, ex.getMessage(), ex));
    }

    // ── FindByUserId ──────────────────────────────────────────────────────────

    @Override
    public Flux<Reservation> findByUserId(String userId) {
        return Flux.from(table.scan())
                .flatMap(page -> Flux.fromIterable(page.items()))
                .filter(entity -> userId.equals(entity.getUserId()))
                .map(this::toDomain)
                .doOnSubscribe(sub -> log.debug("Querying reservations by userId={}", userId))
                .doOnError(ex -> log.error("Error querying reservations by userId={}: {}", userId, ex.getMessage(), ex));
    }

    // ── FindExpiredReservations — GSI1 query (O(results), not O(table)) ───────

    /**
     * Queries GSI1 for ACTIVE reservations whose {@code expiresAt} is before {@code before}.
     *
     * <p>GSI1PK = {@code STATUS#ACTIVE} groups all active reservations.
     * GSI1SK = expiresAt (ISO-8601) allows range query: {@code GSI1SK <= before.toString()}.
     * ISO-8601 strings compare lexicographically in the same order as chronologically.
     */
    @Override
    public Flux<Reservation> findExpiredReservations(Instant before) {
        QueryConditional queryConditional = QueryConditional.sortLessThanOrEqualTo(
                Key.builder()
                        .partitionValue(STATUS_GSI_PREFIX + ReservationStatus.ACTIVE.name())
                        .sortValue(before.toString())
                        .build()
        );

        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(queryConditional)
                .build();

        return Flux.from(table.index(GSI1_INDEX_NAME).query(request))
                .flatMap(page -> Flux.fromIterable(page.items()))
                .map(this::toDomain)
                .doOnSubscribe(sub -> log.debug("Querying expired reservations (GSI1) before={}", before))
                .doOnError(ex -> log.error("Error querying expired reservations: {}", ex.getMessage(), ex));
    }

    // ── UpdateStatus ──────────────────────────────────────────────────────────

    /**
     * Updates the reservation status using a raw UpdateItem expression.
     * Also updates {@code gsi1Pk} and {@code updatedAt} atomically.
     */
    @Override
    public Mono<Reservation> updateStatus(String reservationId, ReservationStatus newStatus) {
        // First find the reservation to get the full key (PK + SK)
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
                            .thenReturn(reservation.status() == ReservationStatus.ACTIVE
                                    ? reservation.cancel()
                                    : reservation.expire())
                            .doOnSuccess(r -> log.debug("Updated reservation status: id={}, newStatus={}", reservationId, newStatus))
                            .doOnError(ex -> log.error("Error updating reservation status: id={}, error={}", reservationId, ex.getMessage(), ex));
                });
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Override
    public Mono<Void> delete(String reservationId) {
        return findById(reservationId)
                .flatMap(reservation -> {
                    Key key = Key.builder()
                            .partitionValue(RESERVATION_PK_PREFIX + reservationId)
                            .sortValue(USER_SK_PREFIX + reservation.userId())
                            .build();
                    return Mono.fromFuture(table.deleteItem(key)).then();
                })
                .doOnSuccess(v -> log.info("Deleted reservation: id={}", reservationId))
                .doOnError(ex -> log.error("Error deleting reservation: id={}, error={}", reservationId, ex.getMessage(), ex));
    }

    // ── Mapping: Domain → Entity ──────────────────────────────────────────────

    private ReservationEntity toEntity(Reservation reservation) {
        ReservationEntity entity = new ReservationEntity();
        entity.setPk(RESERVATION_PK_PREFIX + reservation.id());
        entity.setSk(USER_SK_PREFIX + reservation.userId());
        entity.setGsi1Pk(STATUS_GSI_PREFIX + reservation.status().name());
        entity.setGsi1Sk(reservation.expiresAt().toString());   // ISO-8601, sorts lexicographically
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
