package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import com.nequi.ticketing.domain.exception.DuplicateRequestException;
import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.model.EventId;
import com.nequi.ticketing.domain.model.IdempotencyRecord;
import com.nequi.ticketing.domain.model.Inventory;
import com.nequi.ticketing.domain.port.EventRepository;
import com.nequi.ticketing.infrastructure.config.TicketingProperties;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.millis;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.Attributes.s;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.TransactionWriter.conditionFailed;

@Repository
class DynamoDbEventRepository implements EventRepository {

    private static final int CREATE_IDEMPOTENCY = 2;

    private final DynamoDbAsyncClient dynamoDb;
    private final TransactionWriter writer;
    private final String table;
    private final String idempotencyTable;

    DynamoDbEventRepository(DynamoDbAsyncClient dynamoDb, TransactionWriter writer, TicketingProperties properties) {
        this.dynamoDb = dynamoDb;
        this.writer = writer;
        this.table = properties.tables().events();
        this.idempotencyTable = properties.tables().idempotency();
    }

    @Override
    public Mono<Event> create(Event event, Inventory inventory, IdempotencyRecord idempotency) {
        List<TransactWriteItem> items = List.of(
                newItem(EventItems.item(event)),
                newItem(InventoryItems.item(inventory)),
                IdempotencyItems.create(idempotencyTable, idempotency));
        return writer.write(items, reasons -> conditionFailed(reasons, CREATE_IDEMPOTENCY)
                        ? new DuplicateRequestException(idempotency.key())
                        : new IllegalStateException("Event id collision: " + event.id().value()))
                .thenReturn(event);
    }

    @Override
    public Mono<Event> findById(EventId id) {
        return Mono.fromFuture(() -> dynamoDb.getItem(request -> request.tableName(table).key(EventItems.key(id))))
                .filter(response -> response.hasItem() && !response.item().isEmpty())
                .map(response -> EventItems.fromItem(response.item()));
    }

    @Override
    public Mono<Inventory> findInventory(EventId id) {
        return Mono.fromFuture(() -> dynamoDb.getItem(request -> request
                        .tableName(table)
                        .key(InventoryItems.key(id))
                        .consistentRead(true)))
                .filter(response -> response.hasItem() && !response.item().isEmpty())
                .map(response -> InventoryItems.fromItem(response.item()));
    }

    @Override
    public Flux<Event> findUpcoming(Instant now, int limit) {
        return Mono.fromFuture(() -> dynamoDb.query(request -> request
                        .tableName(table)
                        .indexName(EventItems.LISTING_INDEX)
                        .keyConditionExpression("listingKey = :listing AND eventDate > :now")
                        .expressionAttributeValues(Map.of(":listing", s(EventItems.LISTING_KEY), ":now", millis(now)))
                        .limit(limit)))
                .flatMapIterable(response -> response.items())
                .map(EventItems::fromItem);
    }

    private TransactWriteItem newItem(Map<String, AttributeValue> item) {
        return TransactWriteItem.builder().put(put -> put
                .tableName(table)
                .item(item)
                .conditionExpression("attribute_not_exists(PK)"))
                .build();
    }
}
