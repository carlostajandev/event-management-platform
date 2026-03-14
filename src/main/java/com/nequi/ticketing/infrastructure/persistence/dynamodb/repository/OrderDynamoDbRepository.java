package com.nequi.ticketing.infrastructure.persistence.dynamodb.repository;

import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.repository.OrderRepository;
import com.nequi.ticketing.domain.valueobject.OrderId;
import com.nequi.ticketing.infrastructure.config.AwsProperties;
import com.nequi.ticketing.infrastructure.persistence.dynamodb.entity.OrderDynamoDbEntity;
import com.nequi.ticketing.infrastructure.persistence.dynamodb.mapper.OrderDynamoDbMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.util.Map;

@Repository
public class OrderDynamoDbRepository implements OrderRepository {

    private static final Logger log = LoggerFactory.getLogger(OrderDynamoDbRepository.class);

    private final DynamoDbAsyncTable<OrderDynamoDbEntity> table;
    private final OrderDynamoDbMapper mapper;

    public OrderDynamoDbRepository(
            DynamoDbEnhancedAsyncClient enhancedClient,
            AwsProperties awsProperties,
            OrderDynamoDbMapper mapper) {
        this.table = enhancedClient.table(
                awsProperties.dynamodb().tables().orders(),
                TableSchema.fromBean(OrderDynamoDbEntity.class));
        this.mapper = mapper;
    }

    @Override
    public Mono<Order> save(Order order) {
        OrderDynamoDbEntity entity = mapper.toEntity(order);
        return Mono.fromCompletionStage(() -> table.putItem(entity))
                .thenReturn(order)
                .doOnSuccess(o -> log.debug("Order saved: orderId={}", o.orderId().value()));
    }

    @Override
    public Mono<Order> findById(OrderId orderId) {
        Key key = Key.builder().partitionValue(orderId.value()).build();
        return Mono.fromCompletionStage(() -> table.getItem(key))
                .map(mapper::toDomain);
    }

    @Override
    public Mono<Order> update(Order order) {
        OrderDynamoDbEntity entity = mapper.toEntity(order);

        Expression condition = Expression.builder()
                .expression("#version = :expectedVersion")
                .expressionNames(Map.of("#version", "version"))
                .expressionValues(Map.of(
                        ":expectedVersion",
                        AttributeValue.builder()
                                .n(String.valueOf(order.version() - 1))
                                .build()))
                .build();

        PutItemEnhancedRequest<OrderDynamoDbEntity> request = PutItemEnhancedRequest
                .<OrderDynamoDbEntity>builder(OrderDynamoDbEntity.class)
                .item(entity)
                .conditionExpression(condition)
                .build();

        return Mono.fromCompletionStage(() -> table.putItem(request))
                .thenReturn(order)
                .onErrorMap(ConditionalCheckFailedException.class, ex ->
                        new RuntimeException("Concurrent modification on order: "
                                + order.orderId().value()));
    }
}
