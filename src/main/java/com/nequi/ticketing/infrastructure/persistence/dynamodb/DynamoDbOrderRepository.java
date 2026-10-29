package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import com.nequi.ticketing.domain.exception.DuplicateRequestException;
import com.nequi.ticketing.domain.exception.InsufficientTicketsException;
import com.nequi.ticketing.domain.exception.NotFoundException;
import com.nequi.ticketing.domain.exception.OrderStateChangedException;
import com.nequi.ticketing.domain.model.IdempotencyRecord;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.OrderId;
import com.nequi.ticketing.domain.model.StateTransition;
import com.nequi.ticketing.domain.model.TicketState;
import com.nequi.ticketing.domain.port.OrderRepository;
import com.nequi.ticketing.infrastructure.config.TicketingProperties;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.millis;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.s;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.TransactionWriter.conditionFailed;

@Repository
class DynamoDbOrderRepository implements OrderRepository {

    // Positions of the items inside the create transaction; cancellation reasons come back in the same order.
    private static final int CREATE_INVENTORY = 0;
    private static final int CREATE_ORDER = 1;
    private static final int CREATE_IDEMPOTENCY = 2;
    // Positions inside a transition transaction.
    private static final int TRANSITION_ORDER = 0;

    private final DynamoDbAsyncClient dynamoDb;
    private final TransactionWriter writer;
    private final TicketingProperties.Tables tables;

    DynamoDbOrderRepository(DynamoDbAsyncClient dynamoDb, TransactionWriter writer, TicketingProperties properties) {
        this.dynamoDb = dynamoDb;
        this.writer = writer;
        this.tables = properties.tables();
    }

    @Override
    public Mono<Order> create(StateTransition creation, IdempotencyRecord idempotency) {
        Order order = creation.order();
        List<TransactWriteItem> items = new ArrayList<>(List.of(
                InventoryItems.move(tables.events(), order.eventId(), creation.move()),
                OrderItems.create(tables.orders(), order),
                IdempotencyItems.create(tables.idempotency(), idempotency),
                AuditItems.create(tables.audit(), creation)));
        if (creation.to() == TicketState.RESERVED) {
            items.add(OutboxItems.orderPlaced(tables.outbox(), order, creation.at()));
        }
        return writer.write(items, reasons -> createFailure(reasons, creation, idempotency)).thenReturn(order);
    }

    @Override
    public Mono<Order> apply(StateTransition transition) {
        Order order = transition.order();
        List<TransactWriteItem> items = List.of(
                OrderItems.transition(tables.orders(), transition),
                InventoryItems.move(tables.events(), order.eventId(), transition.move()),
                AuditItems.create(tables.audit(), transition));
        return writer.write(items, reasons -> conditionFailed(reasons, TRANSITION_ORDER)
                        ? new OrderStateChangedException(order.id(), transition.from())
                        : new IllegalStateException("Inventory of event %s is out of sync with order %s"
                                .formatted(order.eventId().value(), order.id().value())))
                .thenReturn(order);
    }

    @Override
    public Mono<Order> findById(OrderId id) {
        return Mono.fromFuture(() -> dynamoDb.getItem(request -> request
                        .tableName(tables.orders())
                        .key(OrderItems.key(id))
                        .consistentRead(true)))
                .filter(response -> response.hasItem() && !response.item().isEmpty())
                .map(response -> OrderItems.fromItem(response.item()));
    }

    @Override
    public Flux<Order> findExpiredHolds(Instant now, int limit) {
        return Flux.just(TicketState.RESERVED, TicketState.PENDING_CONFIRMATION)
                .concatMap(status -> Mono.fromFuture(() -> dynamoDb.query(request -> request
                                .tableName(tables.orders())
                                .indexName(OrderItems.HOLDS_INDEX)
                                .keyConditionExpression("holdStatus = :status AND expiresAt <= :now")
                                .expressionAttributeValues(Map.of(":status", s(status.name()), ":now", millis(now)))
                                .limit(limit)))
                        .flatMapIterable(response -> response.items()))
                .map(OrderItems::fromItem)
                .take(limit);
    }

    /** A replay is reported before a sold-out: the caller may be retrying a request that already succeeded. */
    private static RuntimeException createFailure(List<CancellationReason> reasons, StateTransition creation,
                                                  IdempotencyRecord idempotency) {
        if (conditionFailed(reasons, CREATE_IDEMPOTENCY)) {
            return new DuplicateRequestException(idempotency.key());
        }
        if (conditionFailed(reasons, CREATE_INVENTORY)) {
            CancellationReason inventory = reasons.get(CREATE_INVENTORY);
            if (!inventory.hasItem() || inventory.item().isEmpty()) {
                return new NotFoundException("Event", creation.order().eventId().value());
            }
            return new InsufficientTicketsException(creation.order().eventId(), creation.move().quantity(),
                    InventoryItems.fromItem(inventory.item()).count(creation.move().from()));
        }
        if (conditionFailed(reasons, CREATE_ORDER)) {
            return new IllegalStateException("Order id collision: " + creation.order().id().value());
        }
        return new IllegalStateException("Unexpected condition failure creating order " + creation.order().id().value());
    }
}
