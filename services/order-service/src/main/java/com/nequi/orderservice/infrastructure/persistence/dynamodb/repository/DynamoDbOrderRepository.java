package com.nequi.orderservice.infrastructure.persistence.dynamodb.repository;

import com.nequi.orderservice.infrastructure.persistence.dynamodb.entity.OrderEntity;
import com.nequi.orderservice.infrastructure.persistence.dynamodb.entity.OutboxEntity;
import com.nequi.shared.domain.model.Order;
import com.nequi.shared.domain.model.OrderStatus;
import com.nequi.shared.domain.model.OutboxMessage;
import com.nequi.shared.domain.port.OrderRepository;
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
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * DynamoDB implementation of {@link OrderRepository}.
 *
 * <p>The critical method is {@link #saveWithOutbox}: it uses a DynamoDB
 * {@code TransactWriteItems} call to atomically write the order and outbox message
 * in a single ACID transaction. This guarantees that:
 * <ul>
 *   <li>If DynamoDB succeeds, both items are always written together</li>
 *   <li>If SQS is down after the write, the OutboxPoller will retry SQS delivery</li>
 *   <li>If the transaction fails, neither item is written — no zombie orders</li>
 * </ul>
 *
 * <p>All public methods are protected by {@code @CircuitBreaker(name = "dynamodb")}
 * to open the circuit on sustained DynamoDB failures.
 */
@Slf4j
@Repository
@CircuitBreaker(name = "dynamodb")
public class DynamoDbOrderRepository implements OrderRepository {

    private static final String ORDER_PK_PREFIX       = "ORDER#";
    private static final String RESERVATION_SK_PREFIX = "RESERVATION#";
    private static final String USER_GSI_PREFIX       = "USER#";
    private static final String OUTBOX_PK_PREFIX      = "OUTBOX#";
    private static final String CREATED_AT_SK_PREFIX  = "CREATED_AT#";
    private static final String GSI1_INDEX_NAME       = "GSI1";

    private final DynamoDbEnhancedAsyncClient       enhancedClient;
    private final DynamoDbAsyncClient               rawClient;
    private final DynamoDbAsyncTable<OrderEntity>   orderTable;
    private final String                            orderTableName;
    private final String                            outboxTableName;

    public DynamoDbOrderRepository(
            DynamoDbEnhancedAsyncClient enhancedClient,
            DynamoDbAsyncClient dynamoDbAsyncClient,
            @Value("${aws.dynamodb.table.orders:emp-orders}") String orderTableName,
            @Value("${aws.dynamodb.table.outbox:emp-outbox}") String outboxTableName) {
        this.enhancedClient  = enhancedClient;
        this.rawClient       = dynamoDbAsyncClient;
        this.orderTableName  = orderTableName;
        this.outboxTableName = outboxTableName;
        this.orderTable      = enhancedClient.table(orderTableName, TableSchema.fromBean(OrderEntity.class));
    }

    // ── SaveWithOutbox — CRITICAL atomic write ────────────────────────────────

    /**
     * Writes the Order and an OutboxMessage atomically using DynamoDB TransactWriteItems.
     *
     * <p>This is the cornerstone of the Outbox Pattern. Both items are written in a single
     * ACID transaction — either both succeed or neither is written. The outbox message
     * will be picked up by the OutboxPoller in consumer-service within 5 seconds.
     */
    @Override
    public Mono<Order> saveWithOutbox(Order order, String outboxPayload) {
        Instant now = Instant.now();
        String outboxId = UUID.randomUUID().toString();

        // Build order item attributes
        Map<String, AttributeValue> orderItem = buildOrderItem(order);

        // Build outbox item attributes
        Map<String, AttributeValue> outboxItem = buildOutboxItem(outboxId, order, outboxPayload, now);

        TransactWriteItemsRequest transactRequest = TransactWriteItemsRequest.builder()
                .transactItems(
                        TransactWriteItem.builder()
                                .put(Put.builder()
                                        .tableName(orderTableName)
                                        .item(orderItem)
                                        .build())
                                .build(),
                        TransactWriteItem.builder()
                                .put(Put.builder()
                                        .tableName(outboxTableName)
                                        .item(outboxItem)
                                        .build())
                                .build()
                )
                .build();

        return Mono.fromFuture(rawClient.transactWriteItems(transactRequest))
                .thenReturn(order)
                .doOnSuccess(o -> log.info("Atomic write committed: orderId={}, outboxId={}", o.id(), outboxId))
                .doOnError(ex -> log.error("TransactWriteItems failed: orderId={}, error={}", order.id(), ex.getMessage(), ex));
    }

    // ── FindById ──────────────────────────────────────────────────────────────

    @Override
    public Mono<Order> findById(String orderId) {
        QueryConditional queryConditional = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(ORDER_PK_PREFIX + orderId).build()
        );

        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(queryConditional)
                .limit(1)
                .build();

        return Flux.from(orderTable.query(request))
                .flatMap(page -> Flux.fromIterable(page.items()))
                .next()
                .map(this::toDomain)
                .doOnSuccess(o -> { if (o != null) log.debug("Found order: id={}", orderId); })
                .doOnError(ex -> log.error("Error finding order: id={}, error={}", orderId, ex.getMessage(), ex));
    }

    // ── FindByReservationId ───────────────────────────────────────────────────

    @Override
    public Mono<Order> findByReservationId(String reservationId) {
        // Scan with filter — acceptable for demo; add a GSI for production
        return Flux.from(orderTable.scan())
                .flatMap(page -> Flux.fromIterable(page.items()))
                .filter(entity -> reservationId.equals(entity.getReservationId()))
                .next()
                .map(this::toDomain)
                .doOnError(ex -> log.error("Error finding order by reservationId={}: {}", reservationId, ex.getMessage(), ex));
    }

    // ── FindByUserId ──────────────────────────────────────────────────────────

    @Override
    public Flux<Order> findByUserId(String userId) {
        QueryConditional queryConditional = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(USER_GSI_PREFIX + userId).build()
        );

        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(queryConditional)
                .build();

        return Flux.from(orderTable.index(GSI1_INDEX_NAME).query(request))
                .flatMap(page -> Flux.fromIterable(page.items()))
                .map(this::toDomain)
                .doOnError(ex -> log.error("Error finding orders by userId={}: {}", userId, ex.getMessage(), ex));
    }

    // ── UpdateStatus ──────────────────────────────────────────────────────────

    @Override
    public Mono<Order> updateStatus(String orderId, OrderStatus newStatus) {
        return findById(orderId)
                .flatMap(order -> {
                    Map<String, AttributeValue> key = new HashMap<>();
                    key.put("PK", AttributeValue.fromS(ORDER_PK_PREFIX + orderId));
                    key.put("SK", AttributeValue.fromS(RESERVATION_SK_PREFIX + order.reservationId()));

                    Map<String, AttributeValue> expressionValues = new HashMap<>();
                    expressionValues.put(":newStatus", AttributeValue.fromS(newStatus.name()));
                    expressionValues.put(":updatedAt", AttributeValue.fromS(Instant.now().toString()));

                    Map<String, String> expressionNames = new HashMap<>();
                    expressionNames.put("#s", "status");

                    UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                            .tableName(orderTableName)
                            .key(key)
                            .updateExpression("SET #s = :newStatus, updatedAt = :updatedAt")
                            .expressionAttributeValues(expressionValues)
                            .expressionAttributeNames(expressionNames)
                            .returnValues(ReturnValue.ALL_NEW)
                            .build();

                    return Mono.fromFuture(rawClient.updateItem(updateRequest))
                            .thenReturn(new Order(
                                    order.id(), order.reservationId(), order.eventId(),
                                    order.userId(), order.seatsCount(), order.totalAmount(),
                                    order.currency(), newStatus, order.idempotencyKey(),
                                    order.createdAt(), Instant.now()
                            ))
                            .doOnSuccess(o -> log.info("Order status updated: orderId={}, newStatus={}", orderId, newStatus))
                            .doOnError(ex -> log.error("Error updating order status: orderId={}, error={}", orderId, ex.getMessage(), ex));
                });
    }

    // ── Item builders for raw TransactWriteItems ──────────────────────────────

    private Map<String, AttributeValue> buildOrderItem(Order order) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK",             AttributeValue.fromS(ORDER_PK_PREFIX + order.id()));
        item.put("SK",             AttributeValue.fromS(RESERVATION_SK_PREFIX + order.reservationId()));
        item.put("GSI1PK",         AttributeValue.fromS(USER_GSI_PREFIX + order.userId()));
        item.put("GSI1SK",         AttributeValue.fromS(order.createdAt().toString()));
        item.put("id",             AttributeValue.fromS(order.id()));
        item.put("reservationId",  AttributeValue.fromS(order.reservationId()));
        item.put("eventId",        AttributeValue.fromS(order.eventId()));
        item.put("userId",         AttributeValue.fromS(order.userId()));
        item.put("seatsCount",     AttributeValue.fromN(String.valueOf(order.seatsCount())));
        item.put("totalAmount",    AttributeValue.fromS(order.totalAmount().toPlainString()));
        item.put("currency",       AttributeValue.fromS(order.currency()));
        item.put("status",         AttributeValue.fromS(order.status().name()));
        item.put("idempotencyKey", AttributeValue.fromS(order.idempotencyKey()));
        item.put("createdAt",      AttributeValue.fromS(order.createdAt().toString()));
        item.put("updatedAt",      AttributeValue.fromS(order.updatedAt().toString()));
        return item;
    }

    private Map<String, AttributeValue> buildOutboxItem(
            String outboxId, Order order, String payload, Instant now) {
        Map<String, AttributeValue> item = new HashMap<>();
        long ttl = now.plusSeconds(86_400L).getEpochSecond(); // 24h TTL
        item.put("PK",            AttributeValue.fromS(OUTBOX_PK_PREFIX + outboxId));
        item.put("SK",            AttributeValue.fromS(CREATED_AT_SK_PREFIX + now));
        item.put("GSI1PK",        AttributeValue.fromS("PUBLISHED#false"));
        item.put("id",            AttributeValue.fromS(outboxId));
        item.put("aggregateId",   AttributeValue.fromS(order.id()));
        item.put("aggregateType", AttributeValue.fromS("ORDER"));
        item.put("eventType",     AttributeValue.fromS("ORDER_PLACED"));
        item.put("payload",       AttributeValue.fromS(payload));
        item.put("published",     AttributeValue.fromBool(false));
        item.put("createdAt",     AttributeValue.fromS(now.toString()));
        item.put("ttl",           AttributeValue.fromN(String.valueOf(ttl)));
        return item;
    }

    // ── Mapping: Entity → Domain ──────────────────────────────────────────────

    private Order toDomain(OrderEntity entity) {
        return new Order(
                entity.getId(),
                entity.getReservationId(),
                entity.getEventId(),
                entity.getUserId(),
                entity.getSeatsCount(),
                new BigDecimal(entity.getTotalAmount()),
                entity.getCurrency(),
                OrderStatus.valueOf(entity.getStatus()),
                entity.getIdempotencyKey(),
                Instant.parse(entity.getCreatedAt()),
                Instant.parse(entity.getUpdatedAt())
        );
    }
}
