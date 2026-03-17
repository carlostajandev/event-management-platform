package com.nequi.consumerservice.infrastructure.persistence.dynamodb.repository;

import com.nequi.consumerservice.infrastructure.persistence.dynamodb.entity.OrderEntity;
import com.nequi.shared.domain.model.Order;
import com.nequi.shared.domain.model.OrderStatus;
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
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * DynamoDB implementation of {@link OrderRepository} for consumer-service.
 *
 * <p>Consumer-service only needs to read orders and update their status.
 * The {@link #saveWithOutbox} method is not used here (owned by order-service),
 * but must be implemented to satisfy the port contract — it throws
 * {@link UnsupportedOperationException}.
 *
 * <p>All public methods protected by {@code @CircuitBreaker(name = "dynamodb")}.
 */
@Slf4j
@Repository
@CircuitBreaker(name = "dynamodb")
public class DynamoDbOrderRepository implements OrderRepository {

    private static final String ORDER_PK_PREFIX       = "ORDER#";
    private static final String RESERVATION_SK_PREFIX = "RESERVATION#";
    private static final String USER_GSI_PREFIX       = "USER#";
    private static final String GSI1_INDEX_NAME       = "GSI1";

    private final DynamoDbEnhancedAsyncClient       enhancedClient;
    private final DynamoDbAsyncClient               rawClient;
    private final DynamoDbAsyncTable<OrderEntity>   orderTable;
    private final String                            orderTableName;

    public DynamoDbOrderRepository(
            DynamoDbEnhancedAsyncClient enhancedClient,
            DynamoDbAsyncClient dynamoDbAsyncClient,
            @Value("${aws.dynamodb.table.orders:emp-orders}") String orderTableName) {
        this.enhancedClient = enhancedClient;
        this.rawClient      = dynamoDbAsyncClient;
        this.orderTableName = orderTableName;
        this.orderTable     = enhancedClient.table(orderTableName, TableSchema.fromBean(OrderEntity.class));
    }

    // ── Not used in consumer-service (owned by order-service) ─────────────────

    @Override
    public Mono<Order> saveWithOutbox(Order order, String outboxPayload) {
        return Mono.error(new UnsupportedOperationException(
                "saveWithOutbox is owned by order-service. consumer-service only reads/updates orders."));
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

        return Flux.from(orderTable.index(GSI1_INDEX_NAME).query(
                        QueryEnhancedRequest.builder().queryConditional(queryConditional).build()))
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

    // ── Mapping ───────────────────────────────────────────────────────────────

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
