package com.nequi.reservationservice.infrastructure.persistence.dynamodb.repository;

import com.nequi.reservationservice.infrastructure.persistence.dynamodb.entity.EventEntity;
import com.nequi.shared.domain.exception.ConcurrentModificationException;
import com.nequi.shared.domain.model.Event;
import com.nequi.shared.domain.model.EventStatus;
import com.nequi.shared.domain.model.Venue;
import com.nequi.shared.domain.port.EventRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Read-side DynamoDB adapter for the {@code emp-events} table, used by reservation-service.
 *
 * <p>reservation-service needs to look up events (findById) and perform atomic ticket
 * operations (reserveTickets / releaseTickets) against the same emp-events table that
 * event-service owns. Each service must provide its own adapter bean.
 */
@Slf4j
@Repository
@CircuitBreaker(name = "dynamodb")
public class DynamoDbEventRepository implements EventRepository {

    private static final String EVENT_PK_PREFIX   = "EVENT#";
    private static final String METADATA_SK       = "METADATA";
    private static final String STATUS_GSI_PREFIX = "STATUS#";
    private static final String GSI1_INDEX_NAME   = "GSI1";

    private final DynamoDbEnhancedAsyncClient         enhancedClient;
    private final DynamoDbAsyncClient                 rawClient;
    private final DynamoDbAsyncTable<EventEntity>     table;
    private final String                              tableName;

    public DynamoDbEventRepository(
            DynamoDbEnhancedAsyncClient enhancedClient,
            DynamoDbAsyncClient dynamoDbAsyncClient,
            @Value("${aws.dynamodb.table.events:emp-events}") String tableName) {
        this.enhancedClient = enhancedClient;
        this.rawClient      = dynamoDbAsyncClient;
        this.tableName      = tableName;
        this.table          = enhancedClient.table(tableName, TableSchema.fromBean(EventEntity.class));
    }

    @Override
    public Mono<Event> save(Event event) {
        throw new UnsupportedOperationException("reservation-service does not own the events table");
    }

    @Override
    public Mono<Event> findById(String eventId) {
        Key key = Key.builder()
                .partitionValue(EVENT_PK_PREFIX + eventId)
                .sortValue(METADATA_SK)
                .build();

        return Mono.fromFuture(table.getItem(key))
                .flatMap(entity -> entity != null ? Mono.just(toDomain(entity)) : Mono.empty())
                .doOnError(ex -> log.error("Error finding event: id={}, error={}", eventId, ex.getMessage(), ex));
    }

    @Override
    public Flux<Event> findByStatus(EventStatus status) {
        throw new UnsupportedOperationException("reservation-service does not need findByStatus on events");
    }

    @Override
    public Mono<Event> reserveTickets(String eventId, int seatsCount, long expectedVersion) {
        Map<String, AttributeValue> key = buildKey(eventId);

        Map<String, AttributeValue> expressionValues = new HashMap<>();
        expressionValues.put(":n",        AttributeValue.fromN(String.valueOf(seatsCount)));
        expressionValues.put(":expected", AttributeValue.fromN(String.valueOf(expectedVersion)));
        expressionValues.put(":one",      AttributeValue.fromN("1"));

        Map<String, String> expressionNames = new HashMap<>();
        expressionNames.put("#v", "version");

        UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .updateExpression("SET availableCount = availableCount - :n, #v = #v + :one")
                .conditionExpression("availableCount >= :n AND #v = :expected")
                .expressionAttributeValues(expressionValues)
                .expressionAttributeNames(expressionNames)
                .returnValues(ReturnValue.ALL_NEW)
                .build();

        return Mono.fromFuture(rawClient.updateItem(updateRequest))
                .map(this::responseToEvent)
                .onErrorMap(ConditionalCheckFailedException.class,
                        ex -> new ConcurrentModificationException(eventId))
                .doOnError(ConcurrentModificationException.class, ex ->
                    log.warn("Concurrent modification on reserve: eventId={}, expectedVersion={}", eventId, expectedVersion))
                .doOnError(ex -> {
                    if (!(ex instanceof ConcurrentModificationException)) {
                        log.error("Error reserving tickets: eventId={}, error={}", eventId, ex.getMessage(), ex);
                    }
                });
    }

    @Override
    public Mono<Event> releaseTickets(String eventId, int seatsCount) {
        Map<String, AttributeValue> key = buildKey(eventId);

        Map<String, AttributeValue> expressionValues = new HashMap<>();
        expressionValues.put(":n",   AttributeValue.fromN(String.valueOf(seatsCount)));
        expressionValues.put(":one", AttributeValue.fromN("1"));

        Map<String, String> expressionNames = new HashMap<>();
        expressionNames.put("#v", "version");

        UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .updateExpression("SET availableCount = availableCount + :n, #v = #v + :one")
                .expressionAttributeValues(expressionValues)
                .expressionAttributeNames(expressionNames)
                .returnValues(ReturnValue.ALL_NEW)
                .build();

        return Mono.fromFuture(rawClient.updateItem(updateRequest))
                .map(this::responseToEvent)
                .doOnError(ex -> log.error("Error releasing tickets: eventId={}, error={}", eventId, ex.getMessage(), ex));
    }

    @Override
    public Mono<Void> delete(String eventId) {
        throw new UnsupportedOperationException("reservation-service does not own the events table");
    }

    // ── Mapping: Entity → Domain ──────────────────────────────────────────────

    private Event toDomain(EventEntity entity) {
        Venue venue = new Venue(
                entity.getVenueName(),
                entity.getVenueAddress(),
                entity.getVenueCity(),
                entity.getVenueCountry(),
                entity.getVenueCapacity()
        );
        return new Event(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                venue,
                Instant.parse(entity.getEventDate()),
                new BigDecimal(entity.getTicketPrice()),
                entity.getCurrency(),
                entity.getTotalCapacity(),
                entity.getAvailableCount(),
                entity.getVersion() != null ? entity.getVersion() : 0L,
                EventStatus.valueOf(entity.getStatus()),
                Instant.parse(entity.getCreatedAt()),
                Instant.parse(entity.getUpdatedAt())
        );
    }

    private Event responseToEvent(UpdateItemResponse response) {
        Map<String, AttributeValue> attrs = response.attributes();
        Venue venue = new Venue(
                getStringAttr(attrs, "venueName"),
                getStringAttr(attrs, "venueAddress"),
                getStringAttr(attrs, "venueCity"),
                getStringAttr(attrs, "venueCountry"),
                getIntAttr(attrs, "venueCapacity")
        );
        return new Event(
                getStringAttr(attrs, "id"),
                getStringAttr(attrs, "name"),
                getStringAttr(attrs, "description"),
                venue,
                Instant.parse(getStringAttr(attrs, "eventDate")),
                new BigDecimal(getStringAttr(attrs, "ticketPrice")),
                getStringAttr(attrs, "currency"),
                getIntAttr(attrs, "totalCapacity"),
                getIntAttr(attrs, "availableCount"),
                getLongAttr(attrs, "version"),
                EventStatus.valueOf(getStringAttr(attrs, "status")),
                Instant.parse(getStringAttr(attrs, "createdAt")),
                Instant.parse(getStringAttr(attrs, "updatedAt"))
        );
    }

    // ── DynamoDB key builder ──────────────────────────────────────────────────

    private Map<String, AttributeValue> buildKey(String eventId) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("PK", AttributeValue.fromS(EVENT_PK_PREFIX + eventId));
        key.put("SK", AttributeValue.fromS(METADATA_SK));
        return key;
    }

    // ── Attribute extraction helpers ──────────────────────────────────────────

    private String getStringAttr(Map<String, AttributeValue> attrs, String key) {
        AttributeValue val = attrs.get(key);
        return val != null ? val.s() : null;
    }

    private int getIntAttr(Map<String, AttributeValue> attrs, String key) {
        AttributeValue val = attrs.get(key);
        return val != null && val.n() != null ? Integer.parseInt(val.n()) : 0;
    }

    private long getLongAttr(Map<String, AttributeValue> attrs, String key) {
        AttributeValue val = attrs.get(key);
        return val != null && val.n() != null ? Long.parseLong(val.n()) : 0L;
    }
}
