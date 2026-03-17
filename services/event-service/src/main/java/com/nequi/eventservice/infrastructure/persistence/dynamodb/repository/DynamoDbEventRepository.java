package com.nequi.eventservice.infrastructure.persistence.dynamodb.repository;

import com.nequi.eventservice.infrastructure.persistence.dynamodb.entity.EventEntity;
import com.nequi.shared.domain.exception.ConcurrentModificationException;
import com.nequi.shared.domain.exception.EventNotFoundException;
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
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
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
 * DynamoDB implementation of {@link EventRepository}.
 *
 * <p>Uses two clients:
 * <ul>
 *   <li>{@link DynamoDbEnhancedAsyncClient} — high-level ORM for save/get/query operations</li>
 *   <li>{@link DynamoDbAsyncClient} — low-level client for atomic conditional writes
 *       (reserveTickets / releaseTickets) where we need fine-grained control over
 *       UpdateExpression and ConditionExpression</li>
 * </ul>
 *
 * <p>All public methods are annotated with {@code @CircuitBreaker(name = "dynamodb")}
 * so Resilience4j opens the circuit when DynamoDB error rates exceed the configured
 * threshold, failing fast instead of queuing requests.
 */
@Slf4j
@Repository
@CircuitBreaker(name = "dynamodb")
public class DynamoDbEventRepository implements EventRepository {

    private static final String EVENT_PK_PREFIX  = "EVENT#";
    private static final String METADATA_SK      = "METADATA";
    private static final String STATUS_GSI_PREFIX = "STATUS#";
    private static final String GSI1_INDEX_NAME  = "GSI1";

    private final DynamoDbEnhancedAsyncClient enhancedClient;
    private final DynamoDbAsyncClient         rawClient;
    private final DynamoDbAsyncTable<EventEntity> table;
    private final String tableName;

    public DynamoDbEventRepository(
            DynamoDbEnhancedAsyncClient enhancedClient,
            DynamoDbAsyncClient dynamoDbAsyncClient,
            @Value("${aws.dynamodb.table.events:emp-events}") String tableName) {
        this.enhancedClient = enhancedClient;
        this.rawClient      = dynamoDbAsyncClient;
        this.tableName      = tableName;
        this.table          = enhancedClient.table(tableName, TableSchema.fromBean(EventEntity.class));
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    @Override
    public Mono<Event> save(Event event) {
        EventEntity entity = toEntity(event);
        return Mono.fromFuture(table.putItem(entity))
                .thenReturn(event)
                .doOnSuccess(e -> log.debug("Saved event: id={}", e.id()))
                .doOnError(ex -> log.error("Failed to save event: id={}, error={}", event.id(), ex.getMessage(), ex));
    }

    // ── FindById ──────────────────────────────────────────────────────────────

    @Override
    public Mono<Event> findById(String eventId) {
        Key key = Key.builder()
                .partitionValue(EVENT_PK_PREFIX + eventId)
                .sortValue(METADATA_SK)
                .build();

        return Mono.fromFuture(table.getItem(key))
                .flatMap(entity -> entity != null ? Mono.just(toDomain(entity)) : Mono.empty())
                .doOnSuccess(event -> {
                    if (event != null) log.debug("Found event: id={}", eventId);
                })
                .doOnError(ex -> log.error("Error finding event: id={}, error={}", eventId, ex.getMessage(), ex));
    }

    // ── FindByStatus (GSI1 query) ─────────────────────────────────────────────

    @Override
    public Flux<Event> findByStatus(EventStatus status) {
        QueryConditional queryConditional = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(STATUS_GSI_PREFIX + status.name()).build()
        );

        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(queryConditional)
                .build();

        return Flux.from(table.index(GSI1_INDEX_NAME).query(request))
                .flatMap(page -> Flux.fromIterable(page.items()))
                .map(this::toDomain)
                .doOnSubscribe(sub -> log.debug("Querying events by status={}", status))
                .doOnError(ex -> log.error("Error querying events by status={}: {}", status, ex.getMessage(), ex));
    }

    // ── ReserveTickets — atomic conditional decrement ─────────────────────────

    /**
     * Atomically decrements {@code availableCount} by {@code seatsCount} using
     * a DynamoDB conditional write.
     *
     * <p>UpdateExpression:  {@code SET availableCount = availableCount - :n, #v = #v + 1}
     * <p>ConditionExpression: {@code availableCount >= :n AND #v = :expected}
     *
     * <p>On {@link ConditionalCheckFailedException} throws {@link ConcurrentModificationException}
     * so the caller can apply exponential backoff retry and return HTTP 409.
     */
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
                .doOnSuccess(e -> log.debug("Reserved {} tickets for event: id={}", seatsCount, eventId))
                .doOnError(ConcurrentModificationException.class, ex ->
                    log.warn("Concurrent modification on reserve: eventId={}, expectedVersion={}", eventId, expectedVersion)
                )
                .doOnError(ex -> {
                    if (!(ex instanceof ConcurrentModificationException)) {
                        log.error("Error reserving tickets: eventId={}, error={}", eventId, ex.getMessage(), ex);
                    }
                });
    }

    // ── ReleaseTickets — atomic increment ─────────────────────────────────────

    /**
     * Atomically increments {@code availableCount} by {@code seatsCount}.
     *
     * <p>Called on reservation expiry or order failure. No version check needed
     * here because we are adding back tickets that were already deducted; the
     * ADD expression is commutative and safe under concurrent execution.
     */
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
                .doOnSuccess(e -> log.debug("Released {} tickets for event: id={}", seatsCount, eventId))
                .doOnError(ex -> log.error("Error releasing tickets: eventId={}, error={}", eventId, ex.getMessage(), ex));
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Override
    public Mono<Void> delete(String eventId) {
        Key key = Key.builder()
                .partitionValue(EVENT_PK_PREFIX + eventId)
                .sortValue(METADATA_SK)
                .build();

        return Mono.fromFuture(table.deleteItem(key))
                .then()
                .doOnSuccess(v -> log.info("Deleted event: id={}", eventId))
                .doOnError(ex -> log.error("Error deleting event: id={}, error={}", eventId, ex.getMessage(), ex));
    }

    // ── Mapping: Domain → Entity ──────────────────────────────────────────────

    private EventEntity toEntity(Event event) {
        EventEntity entity = new EventEntity();
        entity.setPk(EVENT_PK_PREFIX + event.id());
        entity.setSk(METADATA_SK);
        entity.setGsi1Pk(STATUS_GSI_PREFIX + event.status().name());
        entity.setId(event.id());
        entity.setName(event.name());
        entity.setDescription(event.description());
        entity.setVenueName(event.venue().name());
        entity.setVenueAddress(event.venue().address());
        entity.setVenueCity(event.venue().city());
        entity.setVenueCountry(event.venue().country());
        entity.setVenueCapacity(event.venue().capacity());
        entity.setEventDate(event.eventDate().toString());
        entity.setTicketPrice(event.ticketPrice().toPlainString());
        entity.setCurrency(event.currency());
        entity.setTotalCapacity(event.totalCapacity());
        entity.setAvailableCount(event.availableCount());
        entity.setStatus(event.status().name());
        entity.setCreatedAt(event.createdAt().toString());
        entity.setUpdatedAt(event.updatedAt().toString());
        // version is managed by @DynamoDbVersionAttribute — set initial only on create
        entity.setVersion(event.version() == 0L ? null : event.version());
        return entity;
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

    // ── Mapping: UpdateItemResponse → Domain (for raw client operations) ──────

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